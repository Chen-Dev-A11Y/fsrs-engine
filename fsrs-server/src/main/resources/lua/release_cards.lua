-- release_cards.lua
--
-- Atomically returns an overdue card to the due queue after a review is committed to MySQL,
-- and drops it from the served set so it can be handed out again.
--
-- The card's new due instant is already durable in MySQL at this point; the script only makes
-- the Redis index agree with it. Doing both updates in one unit matters because a card that
-- left the served set but never got its new score would be handed out again immediately.
--
-- KEYS[1] srs:due:{userId}      ZSet
-- KEYS[2] srs:session:{userId}  Set
-- KEYS[3] srs:newset:{userId}   Set
-- ARGV[1] cardId
-- ARGV[2] newDueMillis          score to write back (epoch millis UTC)
-- ARGV[3] unlockAtMillis        EXPIREAT for both sets
--
-- Returns 1.

local cardId = ARGV[1]
local newDue = tonumber(ARGV[2])
local unlockAt = tonumber(ARGV[3])

redis.call('ZADD', KEYS[1], newDue, cardId)
redis.call('SREM', KEYS[2], cardId)
redis.call('SREM', KEYS[3], cardId)
redis.call('EXPIREAT', KEYS[2], unlockAt)
redis.call('EXPIREAT', KEYS[3], unlockAt)

return 1
