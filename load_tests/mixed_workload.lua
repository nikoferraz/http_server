-- Mixed workload test with wrk
-- Tests different file sizes: small (50%), medium (30%), large (20%)
-- Usage: wrk -t12 -c400 -d30s -s mixed_workload.lua http://localhost:8080/

local files = {
   -- Small files (50% probability)
   "/index.html",
   "/index.html",
   "/small.html",
   "/small.html",
   "/small.html",
   -- Medium files (30% probability)
   "/medium.txt",
   "/medium.txt",
   "/medium.txt",
   -- Large files (20% probability)
   "/large.pdf",
   "/large.pdf"
}

local request_count = 0
local small_count = 0
local medium_count = 0
local large_count = 0

wrk.method = "GET"

-- Called once per request
request = function()
   request_count = request_count + 1
   -- Randomly select file based on distribution
   local file_index = math.random(#files)
   local path = files[file_index]

   -- Track distribution
   if path:find("small") or path:find("index") then
      small_count = small_count + 1
   elseif path:find("medium") then
      medium_count = medium_count + 1
   else
      large_count = large_count + 1
   end

   return wrk.format(nil, path)
end

-- Called once per response
response = function(status, headers, body)
   if status ~= 200 then
      print("Error: HTTP " .. status)
   end
end

-- Called once per thread after the test completes
done = function(summary, latency, requests)
   print("\n========== MIXED WORKLOAD TEST RESULTS ==========")
   print(string.format("Total Requests: %d", summary.requests))
   print(string.format("Throughput: %.0f req/sec", summary.requests / summary.duration * 1000000))
   print(string.format("Small files: %d (%.1f%%)", small_count, small_count / summary.requests * 100))
   print(string.format("Medium files: %d (%.1f%%)", medium_count, medium_count / summary.requests * 100))
   print(string.format("Large files: %d (%.1f%%)", large_count, large_count / summary.requests * 100))
   print(string.format("Latency p50: %.2f ms", latency.mean / 1000))
   print(string.format("Latency p95: %.2f ms", latency.percentile(95) / 1000))
   print(string.format("Latency p99: %.2f ms", latency.percentile(99) / 1000))
   print(string.format("Latency max: %.2f ms", latency.max / 1000))
   print(string.format("Errors: %d", summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.timeout))
   print("================================================\n")
end
