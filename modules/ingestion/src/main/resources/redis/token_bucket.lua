-- Distributed token bucket.
--
-- Runs as one atomic Redis operation, which is the entire point: twenty workers on twenty pods
-- all refill and consume the same bucket, and no interleaving of their reads and writes can let
-- the group collectively exceed the configured rate for a host. Doing this with GET/SET from
-- the client is the classic read-modify-write race, and it fails exactly when it matters —
-- under the concurrency that made you add a rate limiter in the first place.
--
-- KEYS[1] bucket key (one per host)
-- ARGV[1] refill rate, tokens per second
-- ARGV[2] bucket capacity (burst allowance)
-- ARGV[3] caller's wall clock, epoch milliseconds
-- ARGV[4] tokens requested
-- ARGV[5] key TTL in milliseconds
--
-- Returns { allowed (0|1), suggested wait in milliseconds, tokens remaining (millis of a token) }

local key       = KEYS[1]
local rate      = tonumber(ARGV[1])
local capacity  = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local ttl       = tonumber(ARGV[5])

local state  = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

-- A worker whose clock is behind must not be able to mint tokens by rewinding the bucket.
local elapsed = now - ts
if elapsed < 0 then
  elapsed = 0
end

tokens = math.min(capacity, tokens + (elapsed / 1000.0) * rate)

local allowed = 0
local wait = 0

if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  wait = math.ceil(((requested - tokens) / rate) * 1000)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', math.max(ts, now))
redis.call('PEXPIRE', key, ttl)

return { allowed, wait, math.floor(tokens * 1000) }
