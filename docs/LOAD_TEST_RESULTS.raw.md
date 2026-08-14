# Raw load test output

Generated: 2026-08-13T18:17:13Z
Sizes: 100 250 500    Duration per step: 20 min


## 100 services (200 SLOs)

SLOs confirmed present before sampling: 200

```
time="2026-08-13T18:17:52Z" level=info msg="settling for 90s before sampling (lets the evaluator reach steady state)" source=console
time="2026-08-13T18:18:53Z" level=info msg="  settle 67% — 60s / 90s, cycles so far: 5" source=console
time="2026-08-13T18:19:23Z" level=info msg="  settle 100% — 90s / 90s, cycles so far: 7" source=console
time="2026-08-13T18:19:23Z" level=info msg="sampling for 20 minutes — baseline cycle count 7" source=console
time="2026-08-13T18:20:23Z" level=info msg="  sample 5% — 60s / 1200s, cycles so far: 11" source=console
time="2026-08-13T18:21:23Z" level=info msg="  sample 10% — 120s / 1200s, cycles so far: 15" source=console
time="2026-08-13T18:22:23Z" level=info msg="  sample 15% — 180s / 1200s, cycles so far: 19" source=console
time="2026-08-13T18:23:23Z" level=info msg="  sample 20% — 240s / 1200s, cycles so far: 23" source=console
time="2026-08-13T18:24:23Z" level=info msg="  sample 25% — 300s / 1200s, cycles so far: 27" source=console
time="2026-08-13T18:25:23Z" level=info msg="  sample 30% — 360s / 1200s, cycles so far: 31" source=console
time="2026-08-13T18:26:23Z" level=info msg="  sample 35% — 420s / 1200s, cycles so far: 35" source=console
time="2026-08-13T18:27:23Z" level=info msg="  sample 40% — 480s / 1200s, cycles so far: 39" source=console
time="2026-08-13T18:28:23Z" level=info msg="  sample 45% — 540s / 1200s, cycles so far: 43" source=console
time="2026-08-13T18:29:23Z" level=info msg="  sample 50% — 600s / 1200s, cycles so far: 47" source=console
time="2026-08-13T18:30:23Z" level=info msg="  sample 55% — 660s / 1200s, cycles so far: 51" source=console
time="2026-08-13T18:31:23Z" level=info msg="  sample 60% — 720s / 1200s, cycles so far: 55" source=console
time="2026-08-13T18:32:23Z" level=info msg="  sample 65% — 780s / 1200s, cycles so far: 59" source=console
time="2026-08-13T18:33:23Z" level=info msg="  sample 70% — 840s / 1200s, cycles so far: 63" source=console
time="2026-08-13T18:34:23Z" level=info msg="  sample 75% — 900s / 1200s, cycles so far: 67" source=console
time="2026-08-13T18:35:23Z" level=info msg="  sample 80% — 960s / 1200s, cycles so far: 71" source=console
time="2026-08-13T18:36:23Z" level=info msg="  sample 85% — 1020s / 1200s, cycles so far: 75" source=console
time="2026-08-13T18:37:23Z" level=info msg="  sample 90% — 1080s / 1200s, cycles so far: 79" source=console
time="2026-08-13T18:38:23Z" level=info msg="  sample 95% — 1140s / 1200s, cycles so far: 83" source=console
time="2026-08-13T18:39:23Z" level=info msg="  sample 100% — 1200s / 1200s, cycles so far: 87" source=console
time="2026-08-13T18:39:23Z" level=info msg="==== EVALUATION THROUGHPUT ====" source=console
time="2026-08-13T18:39:23Z" level=info msg="SLOs evaluated:      200" source=console
time="2026-08-13T18:39:23Z" level=info msg="Wall seconds:        1200.5" source=console
time="2026-08-13T18:39:23Z" level=info msg="Cycles completed:    80 (expected ~80)" source=console
time="2026-08-13T18:39:23Z" level=info msg="Cycle mean:          31 ms" source=console
time="2026-08-13T18:39:23Z" level=info msg="Cycle p50:           28 ms" source=console
time="2026-08-13T18:39:23Z" level=info msg="Cycle p95:           89 ms" source=console
time="2026-08-13T18:39:23Z" level=info msg="Cycle p99:           179 ms" source=console
time="2026-08-13T18:39:23Z" level=info msg="Interval drift:      0.5 s over 1201 s" source=console
time="2026-08-13T18:39:23Z" level=info msg="Query failures:      0" source=console
time="2026-08-13T18:39:23Z" level=info msg="Evaluations:         17200 total, 17.4% insufficient-data" source=console
time="2026-08-13T18:39:23Z" level=info msg="===============================" source=console

     ✓ sentinel metrics reachable
     ✓ evaluator is running cycles
     ✓ cycles actually ran
     ✓ no query failures
     ✓ evaluator did real work

     checks.........................: 100.00% ✓ 28                   ✗ 0  
     data_received..................: 4.3 MB  3.3 kB/s
     data_sent......................: 2.5 kB  1.9119672852674534 B/s
     http_req_blocked...............: avg=181.72µs min=3.34µs   med=7.34µs  max=4.22ms   p(90)=41.08µs  p(95)=94.2µs  
     http_req_connecting............: avg=28.25µs  min=0s       med=0s      max=706.42µs p(90)=0s       p(95)=0s      
     http_req_duration..............: avg=29.02ms  min=10.8ms   med=24.61ms max=67.11ms  p(90)=51.06ms  p(95)=63.58ms 
       { expected_response:true }...: avg=29.02ms  min=10.8ms   med=24.61ms max=67.11ms  p(90)=51.06ms  p(95)=63.58ms 
     http_req_failed................: 0.00%   ✓ 0                    ✗ 25 
     http_req_receiving.............: avg=5.49ms   min=226.96µs med=1.25ms  max=19.8ms   p(90)=18.1ms   p(95)=19.24ms 
     http_req_sending...............: avg=684.65µs min=23.84µs  med=49.69µs max=15.27ms  p(90)=188.83µs p(95)=437.12µs
     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s      max=0s       p(90)=0s       p(95)=0s      
     http_req_waiting...............: avg=22.84ms  min=8.61ms   med=19.42ms max=65.37ms  p(90)=35.86ms  p(95)=45.94ms 
     http_reqs......................: 25      0.019368/s
     iteration_duration.............: avg=21m30s   min=21m30s   med=21m30s  max=21m30s   p(90)=21m30s   p(95)=21m30s  
     iterations.....................: 1       0.000775/s
     vus............................: 1       min=1                  max=1
     vus_max........................: 1       min=1                  max=1

```

## 250 services (500 SLOs)

SLOs confirmed present before sampling: 500

```
time="2026-08-13T18:40:07Z" level=info msg="settling for 90s before sampling (lets the evaluator reach steady state)" source=console
time="2026-08-13T18:41:07Z" level=info msg="  settle 67% — 60s / 90s, cycles so far: 5" source=console
time="2026-08-13T18:41:37Z" level=info msg="  settle 100% — 90s / 90s, cycles so far: 7" source=console
time="2026-08-13T18:41:37Z" level=info msg="sampling for 20 minutes — baseline cycle count 7" source=console
time="2026-08-13T18:42:37Z" level=info msg="  sample 5% — 60s / 1200s, cycles so far: 11" source=console
time="2026-08-13T18:43:37Z" level=info msg="  sample 10% — 120s / 1200s, cycles so far: 15" source=console
time="2026-08-13T18:44:37Z" level=info msg="  sample 15% — 180s / 1200s, cycles so far: 19" source=console
time="2026-08-13T18:45:37Z" level=info msg="  sample 20% — 240s / 1200s, cycles so far: 23" source=console
time="2026-08-13T18:46:37Z" level=info msg="  sample 25% — 300s / 1200s, cycles so far: 27" source=console
time="2026-08-13T18:47:37Z" level=info msg="  sample 30% — 360s / 1200s, cycles so far: 31" source=console
time="2026-08-13T18:48:38Z" level=info msg="  sample 35% — 420s / 1200s, cycles so far: 35" source=console
time="2026-08-13T18:49:38Z" level=info msg="  sample 40% — 480s / 1200s, cycles so far: 39" source=console
time="2026-08-13T18:50:38Z" level=info msg="  sample 45% — 540s / 1200s, cycles so far: 43" source=console
time="2026-08-13T18:51:38Z" level=info msg="  sample 50% — 600s / 1200s, cycles so far: 47" source=console
time="2026-08-13T18:52:38Z" level=info msg="  sample 55% — 660s / 1200s, cycles so far: 51" source=console
time="2026-08-13T18:53:38Z" level=info msg="  sample 60% — 720s / 1200s, cycles so far: 55" source=console
time="2026-08-13T18:54:38Z" level=info msg="  sample 65% — 780s / 1200s, cycles so far: 59" source=console
time="2026-08-13T18:55:38Z" level=info msg="  sample 70% — 840s / 1200s, cycles so far: 63" source=console
time="2026-08-13T18:56:38Z" level=info msg="  sample 75% — 900s / 1200s, cycles so far: 67" source=console
time="2026-08-13T18:57:38Z" level=info msg="  sample 80% — 960s / 1200s, cycles so far: 71" source=console
time="2026-08-13T18:58:38Z" level=info msg="  sample 85% — 1020s / 1200s, cycles so far: 75" source=console
time="2026-08-13T18:59:38Z" level=info msg="  sample 90% — 1080s / 1200s, cycles so far: 79" source=console
time="2026-08-13T19:00:38Z" level=info msg="  sample 95% — 1140s / 1200s, cycles so far: 83" source=console
time="2026-08-13T19:01:38Z" level=info msg="  sample 100% — 1200s / 1200s, cycles so far: 87" source=console
time="2026-08-13T19:01:38Z" level=info msg="==== EVALUATION THROUGHPUT ====" source=console
time="2026-08-13T19:01:38Z" level=info msg="SLOs evaluated:      500" source=console
time="2026-08-13T19:01:38Z" level=info msg="Wall seconds:        1200.5" source=console
time="2026-08-13T19:01:38Z" level=info msg="Cycles completed:    80 (expected ~80)" source=console
time="2026-08-13T19:01:38Z" level=info msg="Cycle mean:          44 ms" source=console
time="2026-08-13T19:01:38Z" level=info msg="Cycle p50:           45 ms" source=console
time="2026-08-13T19:01:38Z" level=info msg="Cycle p95:           112 ms" source=console
time="2026-08-13T19:01:38Z" level=info msg="Cycle p99:           179 ms" source=console
time="2026-08-13T19:01:38Z" level=info msg="Interval drift:      0.5 s over 1200 s" source=console
time="2026-08-13T19:01:38Z" level=info msg="Query failures:      0" source=console
time="2026-08-13T19:01:38Z" level=info msg="Evaluations:         43000 total, 20.9% insufficient-data" source=console
time="2026-08-13T19:01:38Z" level=info msg="===============================" source=console

     ✓ sentinel metrics reachable
     ✓ evaluator is running cycles
     ✓ cycles actually ran
     ✓ no query failures
     ✓ evaluator did real work

     checks.........................: 100.00% ✓ 28                   ✗ 0  
     data_received..................: 4.3 MB  3.4 kB/s
     data_sent......................: 2.5 kB  1.9122892766744577 B/s
     http_req_blocked...............: avg=123.62µs min=2.46µs   med=7.66µs   max=2.92ms   p(90)=9.14µs  p(95)=9.91µs  
     http_req_connecting............: avg=14.15µs  min=0s       med=0s       max=353.92µs p(90)=0s      p(95)=0s      
     http_req_duration..............: avg=24.08ms  min=9.04ms   med=24.41ms  max=47.51ms  p(90)=39.11ms p(95)=41.22ms 
       { expected_response:true }...: avg=24.08ms  min=9.04ms   med=24.41ms  max=47.51ms  p(90)=39.11ms p(95)=41.22ms 
     http_req_failed................: 0.00%   ✓ 0                    ✗ 25 
     http_req_receiving.............: avg=3.11ms   min=200.99µs med=534.51µs max=22.58ms  p(90)=8.36ms  p(95)=13.87ms 
     http_req_sending...............: avg=56.12µs  min=25.94µs  med=45.99µs  max=113.01µs p(90)=82.53µs p(95)=105.11µs
     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s      p(95)=0s      
     http_req_waiting...............: avg=20.92ms  min=8.75ms   med=19.82ms  max=36.06ms  p(90)=31.84ms p(95)=34.16ms 
     http_reqs......................: 25      0.019371/s
     iteration_duration.............: avg=21m30s   min=21m30s   med=21m30s   max=21m30s   p(90)=21m30s  p(95)=21m30s  
     iterations.....................: 1       0.000775/s
     vus............................: 1       min=1                  max=1
     vus_max........................: 1       min=1                  max=1

```

## 500 services (1000 SLOs)

SLOs confirmed present before sampling: 1000

```
time="2026-08-13T19:02:23Z" level=info msg="settling for 90s before sampling (lets the evaluator reach steady state)" source=console
time="2026-08-13T19:03:23Z" level=info msg="  settle 67% — 60s / 90s, cycles so far: 5" source=console
time="2026-08-13T19:03:53Z" level=info msg="  settle 100% — 90s / 90s, cycles so far: 7" source=console
time="2026-08-13T19:03:53Z" level=info msg="sampling for 20 minutes — baseline cycle count 7" source=console
time="2026-08-13T19:04:53Z" level=info msg="  sample 5% — 60s / 1200s, cycles so far: 11" source=console
time="2026-08-13T19:05:53Z" level=info msg="  sample 10% — 120s / 1200s, cycles so far: 15" source=console
time="2026-08-13T19:06:53Z" level=info msg="  sample 15% — 180s / 1200s, cycles so far: 19" source=console
time="2026-08-13T19:07:53Z" level=info msg="  sample 20% — 240s / 1200s, cycles so far: 23" source=console
time="2026-08-13T19:08:53Z" level=info msg="  sample 25% — 300s / 1200s, cycles so far: 27" source=console
time="2026-08-13T19:09:53Z" level=info msg="  sample 30% — 360s / 1200s, cycles so far: 31" source=console
time="2026-08-13T19:10:53Z" level=info msg="  sample 35% — 420s / 1200s, cycles so far: 35" source=console
time="2026-08-13T19:11:53Z" level=info msg="  sample 40% — 480s / 1200s, cycles so far: 39" source=console
time="2026-08-13T19:12:53Z" level=info msg="  sample 45% — 540s / 1200s, cycles so far: 43" source=console
time="2026-08-13T19:13:53Z" level=info msg="  sample 50% — 600s / 1200s, cycles so far: 47" source=console
time="2026-08-13T19:14:53Z" level=info msg="  sample 55% — 660s / 1200s, cycles so far: 51" source=console
time="2026-08-13T19:15:53Z" level=info msg="  sample 60% — 720s / 1200s, cycles so far: 55" source=console
time="2026-08-13T19:16:53Z" level=info msg="  sample 65% — 780s / 1200s, cycles so far: 59" source=console
time="2026-08-13T19:17:53Z" level=info msg="  sample 70% — 840s / 1200s, cycles so far: 63" source=console
time="2026-08-13T19:18:53Z" level=info msg="  sample 75% — 900s / 1200s, cycles so far: 67" source=console
time="2026-08-13T19:19:53Z" level=info msg="  sample 80% — 960s / 1200s, cycles so far: 71" source=console
time="2026-08-13T19:20:53Z" level=info msg="  sample 85% — 1020s / 1200s, cycles so far: 75" source=console
time="2026-08-13T19:21:53Z" level=info msg="  sample 90% — 1080s / 1200s, cycles so far: 79" source=console
time="2026-08-13T19:22:53Z" level=info msg="  sample 95% — 1140s / 1200s, cycles so far: 83" source=console
time="2026-08-13T19:23:54Z" level=info msg="  sample 100% — 1201s / 1200s, cycles so far: 87" source=console
time="2026-08-13T19:23:54Z" level=info msg="==== EVALUATION THROUGHPUT ====" source=console
time="2026-08-13T19:23:54Z" level=info msg="SLOs evaluated:      1000" source=console
time="2026-08-13T19:23:54Z" level=info msg="Wall seconds:        1200.5" source=console
time="2026-08-13T19:23:54Z" level=info msg="Cycles completed:    80 (expected ~80)" source=console
time="2026-08-13T19:23:54Z" level=info msg="Cycle mean:          59 ms" source=console
time="2026-08-13T19:23:54Z" level=info msg="Cycle p50:           50 ms" source=console
time="2026-08-13T19:23:54Z" level=info msg="Cycle p95:           179 ms" source=console
time="2026-08-13T19:23:54Z" level=info msg="Cycle p99:           246 ms" source=console
time="2026-08-13T19:23:54Z" level=info msg="Interval drift:      0.5 s over 1201 s" source=console
time="2026-08-13T19:23:54Z" level=info msg="Query failures:      0" source=console
time="2026-08-13T19:23:54Z" level=info msg="Evaluations:         86000 total, 19.8% insufficient-data" source=console
time="2026-08-13T19:23:54Z" level=info msg="===============================" source=console

     ✓ sentinel metrics reachable
     ✓ evaluator is running cycles
     ✓ cycles actually ran
     ✓ no query failures
     ✓ evaluator did real work

     checks.........................: 100.00% ✓ 28                   ✗ 0  
     data_received..................: 4.4 MB  3.4 kB/s
     data_sent......................: 2.5 kB  1.9119232659535803 B/s
     http_req_blocked...............: avg=181.76µs min=3.54µs   med=7.3µs   max=4.33ms   p(90)=17.01µs  p(95)=25.02µs 
     http_req_connecting............: avg=39.37µs  min=0s       med=0s      max=984.26µs p(90)=0s       p(95)=0s      
     http_req_duration..............: avg=29.61ms  min=9.94ms   med=22.32ms max=125.19ms p(90)=60.1ms   p(95)=69.75ms 
       { expected_response:true }...: avg=29.61ms  min=9.94ms   med=22.32ms max=125.19ms p(90)=60.1ms   p(95)=69.75ms 
     http_req_failed................: 0.00%   ✓ 0                    ✗ 25 
     http_req_receiving.............: avg=2.62ms   min=140.54µs med=1.17ms  max=19.82ms  p(90)=4.16ms   p(95)=10.89ms 
     http_req_sending...............: avg=59.25µs  min=13.69µs  med=47.69µs max=166.54µs p(90)=107.05µs p(95)=133.94µs
     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s      max=0s       p(90)=0s       p(95)=0s      
     http_req_waiting...............: avg=26.93ms  min=9.57ms   med=19.65ms max=112.53ms p(90)=57.82ms  p(95)=67.85ms 
     http_reqs......................: 25      0.019367/s
     iteration_duration.............: avg=21m30s   min=21m30s   med=21m30s  max=21m30s   p(90)=21m30s   p(95)=21m30s  
     iterations.....................: 1       0.000775/s
     vus............................: 1       min=1                  max=1
     vus_max........................: 1       min=1                  max=1

```

## Duplicate replay (COUNT=10000, SERVICE=synth-c400-s4) — 2026-08-14T18:48:05Z

```
time="2026-08-14T18:48:07Z" level=info msg="baseline: 0 incidents involving synth-c400-s4" source=console
time="2026-08-14T18:48:07Z" level=info msg="published 10000 copies of 53015e82-8d18-35be-a5dd-2202941ac584 in 23ms" source=console
time="2026-08-14T18:48:07Z" level=info msg="draining — waiting for the incident count to hold steady for 45s" source=console
time="2026-08-14T18:48:37Z" level=info msg="  +30s — incidents for synth-c400-s4: 1, steady for 25s of 45s" source=console
time="2026-08-14T18:48:58Z" level=info msg="==== DUPLICATE REPLAY ====" source=console
time="2026-08-14T18:48:58Z" level=info msg="Events replayed:       10000" source=console
time="2026-08-14T18:48:58Z" level=info msg="Incidents created:     1   (expected 1)" source=console
time="2026-08-14T18:48:58Z" level=info msg="Duplicate incidents:   0   (expected 0)" source=console
time="2026-08-14T18:48:58Z" level=info msg="Breach timeline rows:  1   (expected 1)" source=console
time="2026-08-14T18:48:58Z" level=info msg="Drain time:            50.8 s" source=console
time="2026-08-14T18:48:58Z" level=info msg="Dead-lettered:         0   (expected 0)" source=console
time="2026-08-14T18:48:58Z" level=info msg="==========================" source=console

     ✓ replay accepted
     ✓ exactly one incident
     ✓ exactly one breach timeline entry
     ✓ nothing dead-lettered

     checks.........................: 100.00% ✓ 4        ✗ 0  
     data_received..................: 1.1 MB  22 kB/s
     data_sent......................: 2.0 kB  39 B/s
     http_req_blocked...............: avg=116.84µs min=3.53µs   med=6.8µs    max=1.66ms   p(90)=9.22µs  p(95)=505.09µs
     http_req_connecting............: avg=14.84µs  min=0s       med=0s       max=222.7µs  p(90)=0s      p(95)=66.81µs 
     http_req_duration..............: avg=64.07ms  min=13.89ms  med=57.99ms  max=150.64ms p(90)=98.02ms p(95)=122.56ms
       { expected_response:true }...: avg=64.07ms  min=13.89ms  med=57.99ms  max=150.64ms p(90)=98.02ms p(95)=122.56ms
     http_req_failed................: 0.00%   ✓ 0        ✗ 15 
     http_req_receiving.............: avg=1.23ms   min=193.21µs med=865.12µs max=3.77ms   p(90)=2.85ms  p(95)=3.57ms  
     http_req_sending...............: avg=65.06µs  min=13.95µs  med=36.63µs  max=471.65µs p(90)=55.95µs p(95)=185.94µs
     http_req_tls_handshaking.......: avg=0s       min=0s       med=0s       max=0s       p(90)=0s      p(95)=0s      
     http_req_waiting...............: avg=62.78ms  min=13.49ms  med=57.22ms  max=146.68ms p(90)=96.69ms p(95)=120.22ms
     http_reqs......................: 15      0.294149/s
     iteration_duration.............: avg=50.99s   min=50.99s   med=50.99s   max=50.99s   p(90)=50.99s  p(95)=50.99s  
     iterations.....................: 1       0.01961/s
     vus............................: 1       min=1      max=1
     vus_max........................: 1       min=1      max=1

```

## Recovery — 2026-08-14T18:49:01Z

```
==== RECOVERY TEST ====
  This takes roughly 1-3 minutes: kill, restart, wait for two completed cycles,
  then a 20s settle before counting. Progress is dotted while waiting.

  cycles completed so far: 245
  incidents before kill:   200
  duplicate keys before:   0
  killing sentinel mid-cycle...
  restarting...
  waiting for the first completed cycle................. ok
  settling 20s so redelivered messages land before counting...

  Kill-to-steady-state:    40s
  Incidents before:        200
  Incidents after:         200
  Duplicate active keys:   0   (expected 0)
=======================
PASS: no duplicate incidents across the restart
```
