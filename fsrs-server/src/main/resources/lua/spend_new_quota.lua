-- spend_new_quota.lua
--
-- Atomically spends part of the daily new-card allowance.
--
-- INCR followed by a comparison is not safe on its own: two concurrent queue
-- requests could both read "0 used" and both spend the last slot. Doing the
-- increment, the bound check and the expiry setup in one script makes the quota
-- a hard limit instead of a hopeful one.
--
-- The expiry is set on the first spend and is an absolute EXPIREAT at the *user's*
-- next local midnight, so the counter resets on the learner's calendar day, not on
-- the server's. That difference is the whole reason the timezone is a user column.
--
-- KEYS[1] srs:new:{userId}   String counter
-- ARGV[1] requested          how many new cards the caller would like
-- ARGV[2] limit              daily allowance
-- ARGV[3] expireAtSeconds    EXPIREAT in absolute epoch seconds
--
-- Returns {used, granted}

local key = KEYS[1]
local requested = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local expireAt = tonumber(ARGV[3])

local used = tonumber(redis.call('GET', key) or '0')
local remaining = limit - used
if remaining < 0 then
    remaining = 0
end

local granted = requested
if granted > remaining then
    granted = remaining
end

if granted > 0 then
    local after = redis.call('INCRBY', key, granted)
    -- Guard against a key that lost its TTL (for example after a manual FLUSHDB).
    if redis.call('TTL', key) < 0 then
        redis.call('EXPIREAT', key, expireAt)
    end
    return { after, granted }
end

-- Nothing granted: still make sure the counter cannot live forever.
if redis.call('TTL', key) < 0 then
    redis.call('EXPIREAT', key, expireAt)
end
return { used, 0 }
