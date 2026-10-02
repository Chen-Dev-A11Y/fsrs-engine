-- acquire_cards.lua
--
-- Atomically hands out one round's worth of cards for a user.
--
-- Why a script: "which cards are due" and "mark these as handed out" are two steps. Between
-- them a second request for the same user would see the same cards, and the learner would be
-- shown the same card twice - the classic double-issue bug. Redis runs a script as one unit,
-- so the pick and the claim cannot interleave.
--
-- Two sets cooperate, with different jobs:
--   * session (KEYS[2]) - cards handed out in this round. It is what makes a reload of the
--     review page return the cards that are still outstanding instead of the same batch again,
--     and it is the atomic claim that prevents two concurrent requests from overlapping.
--   * newset  (KEYS[3]) - new cards already introduced today. A new card stays here even after
--     it is answered, so the daily allowance cannot be spent twice on it.
--
-- Because a brand-new card is due at "now", it shows up in the due ZSet as well. A new card
-- that has already been introduced today must therefore be skipped by the *due* branch too;
-- without that check the same card is handed out twice, once as new and once as due.
--
-- KEYS[1] srs:due:{userId}      ZSet(score = due_ts, member = cardId)
-- KEYS[2] srs:session:{userId}  Set of cardIds already handed out in this round
-- KEYS[3] srs:newset:{userId}   Set of new cardIds introduced today
-- ARGV[1] nowMillis             cards with score <= now are due
-- ARGV[2] limit                 total cards this round may contain
-- ARGV[3] unlockAtMillis        absolute epoch millis used as EXPIREAT for both sets
--
-- Returns a flat array of {cardId, kind} pairs, kind = "due" | "new".

local now = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local unlockAt = tonumber(ARGV[3])

local out = {}
local taken = 0

local function claim(id, kind, alsoNew)
    redis.call('SADD', KEYS[2], id)
    if alsoNew then
        redis.call('SADD', KEYS[3], id)
        redis.call('EXPIREAT', KEYS[3], unlockAt)
    end
    redis.call('EXPIREAT', KEYS[2], unlockAt)
    out[#out + 1] = id
    out[#out + 1] = kind
    taken = taken + 1
end

if limit > 0 then
    -- SCAN rather than ZRANGEBYSCORE: the served and introduced sets have to be filtered out,
    -- and a plain range would return already-issued cards *and* consume the round's budget with
    -- them. The scan is bounded by the round size, so it stays proportional to limit.
    local cursor = 0
    repeat
        local page = redis.call('ZSCAN', KEYS[1], cursor, 'COUNT', 256)
        cursor = tonumber(page[1])
        local entries = page[2]
        for i = 1, #entries, 2 do
            if taken >= limit then
                break
            end
            local id = entries[i]
            local score = tonumber(entries[i + 1])
            local alreadyServed = redis.call('SISMEMBER', KEYS[2], id) == 1
            local alreadyIntroduced = redis.call('SISMEMBER', KEYS[3], id) == 1
            if score <= now and not alreadyServed and not alreadyIntroduced then
                claim(id, 'due', false)
            end
        end
    until cursor == 0 or taken >= limit

    -- New cards fill whatever budget is left.
    --
    -- ZRANGEBYSCORE, not ZRANGE: the ZSet is scored by due_ts, so a plain ZRANGE would return
    -- the lowest scores in the set and happily hand out a card that is not due until next week
    -- - a *review* card with a future due date is at the head of the set and would be treated
    -- as brand new, destroying its memory state. New cards are those already due, so the same
    -- "score <= now" bound applies.
    if taken < limit then
        local fresh = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, limit * 8)
        for i = 1, #fresh do
            if taken >= limit then
                break
            end
            local id = fresh[i]
            if redis.call('SISMEMBER', KEYS[3], id) == 0
                    and redis.call('SISMEMBER', KEYS[2], id) == 0 then
                claim(id, 'new', true)
            end
        end
    end
end

return out
