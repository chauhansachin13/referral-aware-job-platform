-- Atomically pops up to N boards whose next crawl is due.
--
-- ZRANGEBYSCORE followed by ZREM from the client would hand the same board to two workers
-- whenever their calls interleave, which means two simultaneous requests to the same ATS host
-- and a wasted token from that host's bucket.
--
-- KEYS[1] sorted set of boardId -> next-due epoch millis
-- ARGV[1] now, epoch milliseconds
-- ARGV[2] maximum number of boards to claim
--
-- Returns the claimed board ids.

local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
if #due > 0 then
  redis.call('ZREM', KEYS[1], unpack(due))
end
return due
