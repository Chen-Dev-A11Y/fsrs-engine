-- reserve_idempotency.lua
--
-- Claims a clientReviewId before the review is processed.
--
-- SET NX is the primitive, but it is not enough on its own: a client that retries
-- while the first request is still inside its MySQL transaction would see the key
-- present and wrongly report "duplicate". The record therefore has two states:
--
--   * `PENDING`  - a request owns this id and is still working;
--   * a JSON body - the finished response, replayable verbatim.
--
-- This script implements the claim and the finalise step so that neither can race.
--
-- KEYS[1] srs:idem:{clientReviewId}
-- ARGV[1] mode        'reserve' | 'complete'
-- ARGV[2] payload     'PENDING' for reserve, the serialized response for complete
-- ARGV[3] ttlSeconds  lifetime of the record
--
-- Returns {status, value} with status 'claimed' | 'duplicate' | 'pending' | 'done'

local key = KEYS[1]
local mode = ARGV[1]

if mode == 'reserve' then
    local ok = redis.call('SET', key, ARGV[2], 'NX', 'EX', tonumber(ARGV[3]))
    if ok then
        return { 'claimed', ARGV[2] }
    end
    local current = redis.call('GET', key)
    if current == 'PENDING' then
        return { 'pending', current }
    end
    return { 'done', current }
end

-- mode == 'complete': only the owner leaves PENDING, so a racing request can never
-- overwrite a finished record with its own (possibly stale) result.
local current = redis.call('GET', key)
if current == 'PENDING' then
    redis.call('SET', key, ARGV[2], 'EX', tonumber(ARGV[3]))
elseif not current then
    redis.call('SET', key, ARGV[2], 'EX', tonumber(ARGV[3]))
end
return { 'completed', ARGV[2] }
