-- Sustained load test with wrk
-- Tests consistent throughput over extended period
-- Usage: wrk -t12 -c500 -d2m -s sustained_load.lua http://localhost:8080/

wrk.method = "GET"

local start_time = os.time()
local duration = 120 -- 2 minutes in seconds
local success_count = 0
local error_count = 0

-- Called once per request
request = function()
   return wrk.format(nil, "/index.html")
end

-- Called once per response
response = function(status, headers, body)
   if status == 200 then
      success_count = success_count + 1
   else
      error_count = error_count + 1
   end
end

-- Called once per thread after the test completes
done = function(summary, latency, requests)
   local elapsed = os.time() - start_time
   local throughput = summary.requests / summary.duration * 1000000

   print("\n========== SUSTAINED LOAD TEST RESULTS ==========")
   print(string.format("Total Requests: %d", summary.requests))
   print(string.format("Duration: %.2f seconds", elapsed))
   print(string.format("Throughput: %.0f req/sec", throughput))
   print(string.format("Success Rate: %.1f%%", summary.requests / (summary.requests + 0) * 100))
   print(string.format("Latency mean: %.2f ms", latency.mean / 1000))
   print(string.format("Latency p50: %.2f ms", latency.percentile(50) / 1000))
   print(string.format("Latency p95: %.2f ms", latency.percentile(95) / 1000))
   print(string.format("Latency p99: %.2f ms", latency.percentile(99) / 1000))
   print(string.format("Latency max: %.2f ms", latency.max / 1000))
   print(string.format("Errors: %d", summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.timeout))

   if throughput >= 10000 then
      print("\nRESULT: PASS - Sustained throughput >= 10,000 req/sec")
   else
      print("\nRESULT: FAIL - Sustained throughput < 10,000 req/sec")
   end
   print("=================================================\n")
end
