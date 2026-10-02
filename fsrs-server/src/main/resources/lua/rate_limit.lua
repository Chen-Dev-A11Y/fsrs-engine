-- rate_limit.lua
--
-- Fixed-window throttle for the review endpoint, one window per user.
--
-- A token bucket is the usual answer, but a fixed window expressed as a single
-- INCR + EXPIREAT is both smaller and sufficient: the goal is to stop a script from
-- grinding through a deck (and therefore through the FSRS model) faster than a human
-- could, not to shape a smooth traffic curve. Being explicit about that trade-off
-- matters more than the algorithm.
--
-- KEYS[1] srs:ratelimit:{userId}
-- ARGV[1] maxRequests   allowance per window
-- ARGV[2] windowSeconds window length
--
-- Returns {allowed (0|1), used, ttlSeconds}

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local used = redis.call('INCR', key)
local ttl = redis.call('TTL', key)
if ttl < 0 then
    redis.call('EXPIRE', key, window)
    ttl = window
end

if used > limit then
    return { 0, used, ttl }
end
return { 1, used, ttl }
