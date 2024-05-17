-- Basic load test with wrk
-- Tests simple GET requests to index.html
-- Usage: wrk -t12 -c400 -d10s -s basic_load.lua http://localhost:8080/

wrk.method = "GET"
wrk.path = "/index.html"

-- Called once per request
request = function()
   return wrk.format(nil, "/index.html")
end

-- Called once per response
response = function(status, headers, body)
   if status ~= 200 then
      print("Error: HTTP " .. status)
   end
end

-- Called once per thread after the test completes
done = function(summary, latency, requests)
   print("\n========== BASIC LOAD TEST RESULTS ==========")
   print(string.format("Requests: %d", summary.requests))
   print(string.format("Throughput: %.0f req/sec", summary.requests / summary.duration * 1000000))
   print(string.format("Latency p50: %.2f ms", latency.mean / 1000))
   print(string.format("Latency p99: %.2f ms", latency.percentile(99) / 1000))
   print(string.format("Latency max: %.2f ms", latency.max / 1000))
   print(string.format("Errors: %d", summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.timeout))
   print("============================================\n")
end
