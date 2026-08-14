# Breach storm ramp — measurements 2 and 3

Generated: 2026-08-14T18:05:55Z
Fractions: 0.1 0.2 0.3 0.4 0.5   (cumulative: the exporter breaches chains 0..N)

```
Fleet: 4000 synthetic services, chains of 5 → 800 chains
Dependency edges: 3200 synthetic (expected ~3200)

Baseline: opened=80  active=80  published=7200  redis_zset=400

frac   breach_sv  first_s settle_s   opened  svc_new   ratio  compsz  peak_lag published cycle_ms   dlt
----------------------------------------------------------------------------------------------------------------
0.1          400     none      488        0      400     n/a    5.00       251      3200      311     0
        resources: sentinel=597.7MiB redis=6.379MiB/2.57M redpanda=190.9MiB postgres=59.49MiB prometheus=706.6MiB  [31% of VM]
        redis ZCARD=400   incidents opened cumulative=80   still active=80

0.2          800      129      220       80      400    5.00    5.00       251      2800      352     0
        resources: sentinel=606.5MiB redis=6.176MiB/2.99M redpanda=192.2MiB postgres=61.41MiB prometheus=768.4MiB  [31% of VM]
        redis ZCARD=800   incidents opened cumulative=160   still active=160

0.3         1200      138      232       80      400    5.00    5.00       182      4000      413     0
        resources: sentinel=598.7MiB redis=7.328MiB/3.73M redpanda=192.9MiB postgres=62.65MiB prometheus=853MiB  [33% of VM]
        redis ZCARD=1200   incidents opened cumulative=240   still active=240

0.4         1600      123      218       80      400    5.00    5.00       356      4400      426     0
        resources: sentinel=599MiB redis=7.664MiB/4.27M redpanda=214.1MiB postgres=63.74MiB prometheus=898.3MiB  [33% of VM]
        redis ZCARD=1600   incidents opened cumulative=320   still active=320

0.5         2000      137      228       80      400    5.00    5.00       187      6800      551     0
        resources: sentinel=616MiB redis=9.07MiB/5.13M redpanda=209.5MiB postgres=66.36MiB prometheus=667.2MiB  [31% of VM]
        redis ZCARD=2000   incidents opened cumulative=400   still active=400


Cumulative: opened=400  still active=400  published=32000
Component size overall: 5.00 services per incident
```
