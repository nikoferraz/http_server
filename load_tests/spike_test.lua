-- Spike traffic test with wrk
-- Simulates sudden traffic spikes and recovery
-- Note: This is a simplified version that works with wrk's normal execution
-- Usage: wrk -t12 -c5000 -d30s -s spike_test.lua http://localhost:8080/

local start_time = os.time()
local spike_start = 10 -- Start spike at 10 seconds
local spike_duration = 15 -- Spike lasts 15 seconds
local phase = "normal"
local normal_requests = 0
local spike_requests = 0
local recovery_requests = 0

wrk.method = "GET"

-- Called once per request
request = function()
   local current_time = os.time() - start_time

   if current_time < spike_start then
      phase = "normal"
      normal_requests = normal_requests + 1
   elseif current_time < (spike_start + spike_duration) then
      phase = "spike"
      spike_requests = spike_requests + 1
   else
      phase = "recovery"
      recovery_requests = recovery_requests + 1
   end

   return wrk.format(nil, "/index.html")
end

-- Called once per response
response = function(status, headers, body)
   if status ~= 200 then
      print("Error: HTTP " .. status .. " during " .. phase .. " phase")
   end
end

-- Called once per thread after the test completes
done = function(summary, latency, requests)
   print("\n========== SPIKE TRAFFIC TEST RESULTS ==========")
   print(string.format("Total Requests: %d", summary.requests))
   print(string.format("Normal Phase Requests: %d", normal_requests))
   print(string.format("Spike Phase Requests: %d", spike_requests))
   print(string.format("Recovery Phase Requests: %d", recovery_requests))
   print(string.format("Overall Throughput: %.0f req/sec", summary.requests / summary.duration * 1000000))
   print(string.format("Latency p50: %.2f ms", latency.mean / 1000))
   print(string.format("Latency p99: %.2f ms", latency.percentile(99) / 1000))
   print(string.format("Latency max: %.2f ms", latency.max / 1000))
   print(string.format("Errors: %d", summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.timeout))
   print("===============================================\n")
end
