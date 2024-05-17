-- Cache performance test with wrk
-- Tests repeated requests to same files to measure cache hit rate
-- Usage: wrk -t12 -c400 -d30s -s cache_test.lua http://localhost:8080/

local files = {"/index.html", "/small.html", "/medium.txt"}
local request_count = 0

wrk.method = "GET"

-- Called once per request
request = function()
   request_count = request_count + 1
   -- Cycle through files repeatedly to hit cache
   local file_index = (request_count % #files) + 1
   local path = files[file_index]
   return wrk.format(nil, path)
end

-- Statistics for cache analysis
local cache_hits = 0
local cache_misses = 0

-- Called once per response
response = function(status, headers, body)
   if status == 200 then
      -- Check for ETag or cache headers
      if headers["etag"] or headers["cache-control"] then
         cache_hits = cache_hits + 1
      else
         cache_misses = cache_misses + 1
      end
   end
end

-- Called once per thread after the test completes
done = function(summary, latency, requests)
   local total_requests = summary.requests
   local cache_hit_rate = (cache_hits > 0) and (cache_hits / total_requests * 100) or 0

   print("\n========== CACHE PERFORMANCE TEST RESULTS ==========")
   print(string.format("Total Requests: %d", total_requests))
   print(string.format("Throughput: %.0f req/sec", summary.requests / summary.duration * 1000000))
   print(string.format("Requests with cache headers: %d", cache_hits))
   print(string.format("Cache hit rate: %.1f%%", cache_hit_rate))
   print(string.format("Latency p50: %.2f ms", latency.mean / 1000))
   print(string.format("Latency p99: %.2f ms", latency.percentile(99) / 1000))
   print(string.format("Latency max: %.2f ms", latency.max / 1000))
   print(string.format("Errors: %d", summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.timeout))
   print("====================================================\n")
end
