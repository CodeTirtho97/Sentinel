# Raw load test output

Generated: 2026-08-14T03:57:10Z
Sizes: 1000 2000 4000    Duration per step: 20 min


## 1000 services (2000 SLOs)

SLOs confirmed present before sampling: 2000

```
time="2026-08-14T03:58:03Z" level=info msg="settling for 90s before sampling (lets the evaluator reach steady state)" source=console
time="2026-08-14T03:59:03Z" level=info msg="  settle 67% — 60s / 90s, cycles so far: 5" source=console
time="2026-08-14T03:59:33Z" level=info msg="  settle 100% — 90s / 90s, cycles so far: 7" source=console
time="2026-08-14T03:59:33Z" level=info msg="sampling for 20 minutes — baseline cycle count 7" source=console
time="2026-08-14T04:00:34Z" level=info msg="  sample 5% — 60s / 1200s, cycles so far: 11" source=console
time="2026-08-14T04:01:34Z" level=info msg="  sample 10% — 120s / 1200s, cycles so far: 15" source=console
time="2026-08-14T04:02:34Z" level=info msg="  sample 15% — 180s / 1200s, cycles so far: 19" source=console
time="2026-08-14T04:03:34Z" level=info msg="  sample 20% — 240s / 1200s, cycles so far: 23" source=console
time="2026-08-14T04:04:34Z" level=info msg="  sample 25% — 300s / 1200s, cycles so far: 27" source=console
time="2026-08-14T04:05:34Z" level=info msg="  sample 30% — 360s / 1200s, cycles so far: 31" source=console
time="2026-08-14T04:06:34Z" level=info msg="  sample 35% — 420s / 1200s, cycles so far: 35" source=console
time="2026-08-14T04:07:34Z" level=info msg="  sample 40% — 480s / 1200s, cycles so far: 39" source=console
time="2026-08-14T04:08:34Z" level=info msg="  sample 45% — 540s / 1200s, cycles so far: 43" source=console
time="2026-08-14T04:09:34Z" level=info msg="  sample 50% — 600s / 1200s, cycles so far: 47" source=console
time="2026-08-14T04:10:34Z" level=info msg="  sample 55% — 660s / 1200s, cycles so far: 51" source=console
time="2026-08-14T04:11:34Z" level=info msg="  sample 60% — 720s / 1200s, cycles so far: 55" source=console
time="2026-08-14T04:12:34Z" level=info msg="  sample 65% — 781s / 1200s, cycles so far: 59" source=console
time="2026-08-14T04:13:34Z" level=info msg="  sample 70% — 841s / 1200s, cycles so far: 63" source=console
time="2026-08-14T04:14:34Z" level=info msg="  sample 75% — 901s / 1200s, cycles so far: 67" source=console
time="2026-08-14T04:15:34Z" level=info msg="  sample 80% — 961s / 1200s, cycles so far: 71" source=console
time="2026-08-14T04:16:34Z" level=info msg="  sample 85% — 1021s / 1200s, cycles so far: 75" source=console
time="2026-08-14T04:17:34Z" level=info msg="  sample 90% — 1081s / 1200s, cycles so far: 79" source=console
time="2026-08-14T04:18:34Z" level=info msg="  sample 95% — 1141s / 1200s, cycles so far: 83" source=console
time="2026-08-14T04:19:34Z" level=info msg="  sample 100% — 1201s / 1200s, cycles so far: 87" source=console
time="2026-08-14T04:19:34Z" level=info msg="==== EVALUATION THROUGHPUT ====" source=console
time="2026-08-14T04:19:34Z" level=info msg="SLOs evaluated:      2000" source=console
time="2026-08-14T04:19:34Z" level=info msg="Wall seconds:        1200.8" source=console
time="2026-08-14T04:19:34Z" level=info msg="Cycles completed:    80 (expected ~80)" source=console
time="2026-08-14T04:19:34Z" level=info msg="Cycle mean:          94 ms" source=console
time="2026-08-14T04:19:34Z" level=info msg="Cycle p50:           89 ms" source=console
time="2026-08-14T04:19:34Z" level=info msg="Cycle p95:           201 ms" source=console
time="2026-08-14T04:19:34Z" level=info msg="Cycle p99:           246 ms" source=console
time="2026-08-14T04:19:34Z" level=info msg="Interval drift:      0.8 s over 1201 s" source=console
time="2026-08-14T04:19:34Z" level=info msg="Query failures:      0" source=console
time="2026-08-14T04:19:34Z" level=info msg="Evaluations:         172000 total, 16.3% insufficient-data" source=console
time="2026-08-14T04:19:34Z" level=info msg="===============================" source=console

     ✓ sentinel metrics reachable
     ✓ evaluator is running cycles
     ✓ cycles actually ran
     ✓ no query failures
     ✓ evaluator did real work

     checks.........................: 100.00% ✓ 28                   ✗ 0  
     data_received..................: 4.6 MB  3.6 kB/s
     data_sent......................: 2.6 kB  1.9874606639694814 B/s
     http_req_blocked...............: avg=783.97µs min=3.28µs   med=8.8µs    max=11.24ms  p(90)=28.01µs  p(95)=6.5ms  
     http_req_connecting............: avg=30.06µs  min=0s       med=0s       max=751.59µs p(90)=0s       p(95)=0s     
     http_req_duration..............: avg=33.63ms  min=9.02ms   med=21.63ms  max=142.26ms p(90)=77.45ms  p(95)=83.29ms
       { expected_response:true }...: avg=33.63ms  min=9.02ms   med=21.63ms  max=142.26ms p(90)=77.45ms  p(95)=83.29ms
     http_req_failed................: 0.00%   ✓ 0                    ✗ 25 
     http_req_receiving.............: avg=2.41ms   min=114.26µs med=786.43µs max=19.44ms  p(90)=6.4ms    p(95)=8.16ms 
     http_req_sending...............: avg=834.52µs min=38.41µs  med=62.71µs  max=8.95ms   p(90)=801.74µs p(95)=7.36ms 
     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s       p(95)=0s     
     http_req_waiting...............: avg=30.39ms  min=8.68ms   med=20.93ms  max=122.78ms p(90)=70.64ms  p(95)=78.16ms
     http_reqs......................: 25      0.019363/s
     iteration_duration.............: avg=21m31s   min=21m31s   med=21m31s   max=21m31s   p(90)=21m31s   p(95)=21m31s 
     iterations.....................: 1       0.000775/s
     vus............................: 1       min=1                  max=1
     vus_max........................: 1       min=1                  max=1

```

## 2000 services (4000 SLOs)

SLOs confirmed present before sampling: 4000

```
time="2026-08-14T04:20:25Z" level=info msg="settling for 90s before sampling (lets the evaluator reach steady state)" source=console
time="2026-08-14T04:21:25Z" level=info msg="  settle 67% — 60s / 90s, cycles so far: 5" source=console
time="2026-08-14T04:21:55Z" level=info msg="  settle 100% — 90s / 90s, cycles so far: 7" source=console
time="2026-08-14T04:21:55Z" level=info msg="sampling for 20 minutes — baseline cycle count 7" source=console
time="2026-08-14T04:22:55Z" level=info msg="  sample 5% — 60s / 1200s, cycles so far: 11" source=console
time="2026-08-14T04:23:55Z" level=info msg="  sample 10% — 120s / 1200s, cycles so far: 15" source=console
time="2026-08-14T04:24:55Z" level=info msg="  sample 15% — 180s / 1200s, cycles so far: 19" source=console
time="2026-08-14T04:25:55Z" level=info msg="  sample 20% — 240s / 1200s, cycles so far: 23" source=console
time="2026-08-14T04:26:55Z" level=info msg="  sample 25% — 300s / 1200s, cycles so far: 27" source=console
time="2026-08-14T04:27:55Z" level=info msg="  sample 30% — 360s / 1200s, cycles so far: 31" source=console
time="2026-08-14T04:28:55Z" level=info msg="  sample 35% — 420s / 1200s, cycles so far: 35" source=console
time="2026-08-14T04:29:55Z" level=info msg="  sample 40% — 480s / 1200s, cycles so far: 39" source=console
time="2026-08-14T04:30:55Z" level=info msg="  sample 45% — 540s / 1200s, cycles so far: 43" source=console
time="2026-08-14T04:31:55Z" level=info msg="  sample 50% — 600s / 1200s, cycles so far: 47" source=console
time="2026-08-14T04:32:55Z" level=info msg="  sample 55% — 660s / 1200s, cycles so far: 51" source=console
time="2026-08-14T04:33:55Z" level=info msg="  sample 60% — 720s / 1200s, cycles so far: 55" source=console
time="2026-08-14T04:34:55Z" level=info msg="  sample 65% — 780s / 1200s, cycles so far: 59" source=console
time="2026-08-14T04:35:55Z" level=info msg="  sample 70% — 840s / 1200s, cycles so far: 63" source=console
time="2026-08-14T04:36:55Z" level=info msg="  sample 75% — 900s / 1200s, cycles so far: 67" source=console
time="2026-08-14T04:37:55Z" level=info msg="  sample 80% — 960s / 1200s, cycles so far: 71" source=console
time="2026-08-14T04:38:55Z" level=info msg="  sample 85% — 1020s / 1200s, cycles so far: 75" source=console
time="2026-08-14T04:39:55Z" level=info msg="  sample 90% — 1080s / 1200s, cycles so far: 79" source=console
time="2026-08-14T04:40:55Z" level=info msg="  sample 95% — 1140s / 1200s, cycles so far: 83" source=console
time="2026-08-14T04:41:55Z" level=info msg="  sample 100% — 1200s / 1200s, cycles so far: 87" source=console
time="2026-08-14T04:41:55Z" level=info msg="==== EVALUATION THROUGHPUT ====" source=console
time="2026-08-14T04:41:55Z" level=info msg="SLOs evaluated:      4000" source=console
time="2026-08-14T04:41:55Z" level=info msg="Wall seconds:        1200.4" source=console
time="2026-08-14T04:41:55Z" level=info msg="Cycles completed:    80 (expected ~80)" source=console
time="2026-08-14T04:41:55Z" level=info msg="Cycle mean:          123 ms" source=console
time="2026-08-14T04:41:55Z" level=info msg="Cycle p50:           134 ms" source=console
time="2026-08-14T04:41:55Z" level=info msg="Cycle p95:           246 ms" source=console
time="2026-08-14T04:41:55Z" level=info msg="Cycle p99:           358 ms" source=console
time="2026-08-14T04:41:55Z" level=info msg="Interval drift:      0.4 s over 1200 s" source=console
time="2026-08-14T04:41:55Z" level=info msg="Query failures:      0" source=console
time="2026-08-14T04:41:55Z" level=info msg="Evaluations:         344000 total, 19.8% insufficient-data" source=console
time="2026-08-14T04:41:55Z" level=info msg="===============================" source=console

     ✓ sentinel metrics reachable
     ✓ evaluator is running cycles
     ✓ cycles actually ran
     ✓ no query failures
     ✓ evaluator did real work

     checks.........................: 100.00% ✓ 28                   ✗ 0  
     data_received..................: 5.0 MB  3.8 kB/s
     data_sent......................: 2.5 kB  1.9122472097202872 B/s
     http_req_blocked...............: avg=314.35µs min=2.61µs   med=7.44µs   max=7.65ms   p(90)=14.08µs p(95)=23.04µs 
     http_req_connecting............: avg=12.55µs  min=0s       med=0s       max=313.77µs p(90)=0s      p(95)=0s      
     http_req_duration..............: avg=25.05ms  min=10.02ms  med=20.83ms  max=62.17ms  p(90)=46.05ms p(95)=49.58ms 
       { expected_response:true }...: avg=25.05ms  min=10.02ms  med=20.83ms  max=62.17ms  p(90)=46.05ms p(95)=49.58ms 
     http_req_failed................: 0.00%   ✓ 0                    ✗ 25 
     http_req_receiving.............: avg=2.64ms   min=317.58µs med=756.07µs max=14.12ms  p(90)=9.95ms  p(95)=11.85ms 
     http_req_sending...............: avg=88.86µs  min=33.44µs  med=57.9µs   max=508.59µs p(90)=133.4µs p(95)=159.81µs
     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s      p(95)=0s      
     http_req_waiting...............: avg=22.31ms  min=9.57ms   med=20.29ms  max=48.09ms  p(90)=43.74ms p(95)=47.44ms 
     http_reqs......................: 25      0.01937/s
     iteration_duration.............: avg=21m30s   min=21m30s   med=21m30s   max=21m30s   p(90)=21m30s  p(95)=21m30s  
     iterations.....................: 1       0.000775/s
     vus............................: 1       min=1                  max=1
     vus_max........................: 1       min=1                  max=1

```

## 4000 services (8000 SLOs)

SLOs confirmed present before sampling: 8000

```
time="2026-08-14T04:42:53Z" level=info msg="settling for 90s before sampling (lets the evaluator reach steady state)" source=console
time="2026-08-14T04:43:54Z" level=info msg="  settle 67% — 60s / 90s, cycles so far: 6" source=console
time="2026-08-14T04:44:24Z" level=info msg="  settle 100% — 90s / 90s, cycles so far: 8" source=console
time="2026-08-14T04:44:24Z" level=info msg="sampling for 20 minutes — baseline cycle count 8" source=console
time="2026-08-14T04:45:24Z" level=info msg="  sample 5% — 60s / 1200s, cycles so far: 12" source=console
time="2026-08-14T04:46:24Z" level=info msg="  sample 10% — 120s / 1200s, cycles so far: 16" source=console
time="2026-08-14T04:47:24Z" level=info msg="  sample 15% — 180s / 1200s, cycles so far: 20" source=console
time="2026-08-14T04:48:24Z" level=info msg="  sample 20% — 240s / 1200s, cycles so far: 23" source=console
time="2026-08-14T04:49:24Z" level=info msg="  sample 25% — 300s / 1200s, cycles so far: 27" source=console
time="2026-08-14T04:50:24Z" level=info msg="  sample 30% — 360s / 1200s, cycles so far: 31" source=console
time="2026-08-14T04:51:24Z" level=info msg="  sample 35% — 420s / 1200s, cycles so far: 35" source=console
time="2026-08-14T04:52:24Z" level=info msg="  sample 40% — 480s / 1200s, cycles so far: 39" source=console
time="2026-08-14T04:53:24Z" level=info msg="  sample 45% — 540s / 1200s, cycles so far: 43" source=console
time="2026-08-14T04:54:24Z" level=info msg="  sample 50% — 600s / 1200s, cycles so far: 47" source=console
time="2026-08-14T04:55:24Z" level=info msg="  sample 55% — 660s / 1200s, cycles so far: 51" source=console
time="2026-08-14T04:56:24Z" level=info msg="  sample 60% — 720s / 1200s, cycles so far: 55" source=console
time="2026-08-14T04:57:24Z" level=info msg="  sample 65% — 780s / 1200s, cycles so far: 59" source=console
time="2026-08-14T04:58:24Z" level=info msg="  sample 70% — 840s / 1200s, cycles so far: 63" source=console
time="2026-08-14T04:59:24Z" level=info msg="  sample 75% — 900s / 1200s, cycles so far: 67" source=console
time="2026-08-14T05:00:24Z" level=info msg="  sample 80% — 960s / 1200s, cycles so far: 71" source=console
time="2026-08-14T05:01:24Z" level=info msg="  sample 85% — 1020s / 1200s, cycles so far: 75" source=console
time="2026-08-14T05:02:24Z" level=info msg="  sample 90% — 1080s / 1200s, cycles so far: 79" source=console
time="2026-08-14T05:03:24Z" level=info msg="  sample 95% — 1141s / 1200s, cycles so far: 83" source=console
time="2026-08-14T05:04:24Z" level=info msg="  sample 100% — 1201s / 1200s, cycles so far: 87" source=console
time="2026-08-14T05:04:24Z" level=info msg="==== EVALUATION THROUGHPUT ====" source=console
time="2026-08-14T05:04:24Z" level=info msg="SLOs evaluated:      8000" source=console
time="2026-08-14T05:04:24Z" level=info msg="Wall seconds:        1200.5" source=console
time="2026-08-14T05:04:24Z" level=info msg="Cycles completed:    79 (expected ~80)" source=console
time="2026-08-14T05:04:24Z" level=info msg="Cycle mean:          232 ms" source=console
time="2026-08-14T05:04:24Z" level=info msg="Cycle p50:           246 ms" source=console
time="2026-08-14T05:04:24Z" level=info msg="Cycle p95:           447 ms" source=console
time="2026-08-14T05:04:24Z" level=info msg="Cycle p99:           500 ms" source=console
time="2026-08-14T05:04:24Z" level=info msg="Interval drift:      15.5 s over 1201 s" source=console
time="2026-08-14T05:04:24Z" level=info msg="Query failures:      0" source=console
time="2026-08-14T05:04:24Z" level=info msg="Evaluations:         688000 total, 17.4% insufficient-data" source=console
time="2026-08-14T05:04:24Z" level=info msg="===============================" source=console

     ✓ sentinel metrics reachable
     ✓ evaluator is running cycles
     ✓ cycles actually ran
     ✓ no query failures
     ✓ evaluator did real work

     checks.........................: 100.00% ✓ 28                   ✗ 0  
     data_received..................: 5.7 MB  4.4 kB/s
     data_sent......................: 2.5 kB  1.9118488329691474 B/s
     http_req_blocked...............: avg=733.84µs min=3.34µs   med=8.03µs   max=13.85ms  p(90)=12.19µs  p(95)=3.45ms 
     http_req_connecting............: avg=17.37µs  min=0s       med=0s       max=434.43µs p(90)=0s       p(95)=0s     
     http_req_duration..............: avg=23.66ms  min=9.39ms   med=17.34ms  max=86.8ms   p(90)=40.24ms  p(95)=55.84ms
       { expected_response:true }...: avg=23.66ms  min=9.39ms   med=17.34ms  max=86.8ms   p(90)=40.24ms  p(95)=55.84ms
     http_req_failed................: 0.00%   ✓ 0                    ✗ 25 
     http_req_receiving.............: avg=3.39ms   min=241.75µs med=852.38µs max=26.27ms  p(90)=5.65ms   p(95)=18.39ms
     http_req_sending...............: avg=203.99µs min=35.45µs  med=71.74µs  max=3.2ms    p(90)=133.38µs p(95)=160.4µs
     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s       p(95)=0s     
     http_req_waiting...............: avg=20.06ms  min=8.77ms   med=16.73ms  max=65.32ms  p(90)=30.61ms  p(95)=38.82ms
     http_reqs......................: 25      0.019366/s
     iteration_duration.............: avg=21m31s   min=21m31s   med=21m31s   max=21m31s   p(90)=21m31s   p(95)=21m31s 
     iterations.....................: 1       0.000775/s
     vus............................: 1       min=1                  max=1
     vus_max........................: 1       min=1                  max=1

```
