{{/*
The sentinel-platform pod spec, shared by the Deployment and the sharded StatefulSet so the probe
and shutdown wiring cannot drift between the two.
*/}}
{{- define "sentinel.podSpec" -}}
# 45s, and the three numbers underneath it have to fit inside it:
#   5s   preStop, so kube-proxy has removed this pod from the Service before SIGTERM
#   30s  spring.lifecycle.timeout-per-shutdown-phase, for in-flight work to finish
#   ~10s of headroom
# Overrun and the kubelet sends SIGKILL, which drops whatever the consumer was holding.
terminationGracePeriodSeconds: {{ .Values.sentinel.terminationGracePeriodSeconds }}
# Flyway runs during context startup and fails hard if Postgres is not there yet. Helm does not
# order Deployments, so on a first install this pod can easily win the race — and the symptom is a
# CrashLoopBackOff with a stack trace, which reads like a broken chart rather than a cold start.
# Waiting here turns it into `Init:0/1`, which says what is actually happening.
initContainers:
  - name: wait-for-deps
    image: busybox:1.36
    command:
      - /bin/sh
      - -c
      - |
        until nc -z {{ include "sentinel.postgresHost" . }} 5432; do echo "waiting for postgres"; sleep 2; done
        until nc -z {{ include "sentinel.redpandaHost" . }} 9092; do echo "waiting for redpanda"; sleep 2; done
        until nc -z {{ include "sentinel.redisHost" . }} 6379; do echo "waiting for redis"; sleep 2; done
        echo "dependencies reachable"
    resources:
      requests:
        cpu: 10m
        memory: 16Mi
containers:
  - name: sentinel
    image: "{{ .Values.sentinel.image.repository }}:{{ .Values.sentinel.image.tag }}"
    imagePullPolicy: {{ .Values.sentinel.image.pullPolicy }}
    ports:
      - name: http
        containerPort: 8080
    env:
      - name: SPRING_PROFILES_ACTIVE
        value: {{ .Values.sentinel.profiles | quote }}
      - name: DB_URL
        value: jdbc:postgresql://{{ include "sentinel.postgresHost" . }}:5432/{{ .Values.postgres.database }}
      - name: DB_USER
        value: {{ .Values.postgres.username | quote }}
      - name: DB_PASSWORD
        valueFrom:
          secretKeyRef:
            name: {{ include "sentinel.fullname" . }}-secrets
            key: DB_PASSWORD
      - name: KAFKA_BOOTSTRAP
        value: {{ include "sentinel.redpandaHost" . }}:9092
      - name: REDIS_HOST
        value: {{ include "sentinel.redisHost" . }}
      - name: REDIS_PORT
        value: "6379"
      - name: PROMETHEUS_URL
        value: http://{{ include "sentinel.prometheusHost" . }}:9090
      - name: SENTINEL_API_KEY
        valueFrom:
          secretKeyRef:
            name: {{ include "sentinel.fullname" . }}-secrets
            key: SENTINEL_API_KEY
      - name: LLM_BASE_URL
        value: {{ .Values.sentinel.llm.baseUrl | quote }}
      - name: LLM_MODEL
        value: {{ .Values.sentinel.llm.model | quote }}
      {{- if .Values.sentinel.llm.apiKey }}
      - name: LLM_API_KEY
        valueFrom:
          secretKeyRef:
            name: {{ include "sentinel.fullname" . }}-secrets
            key: LLM_API_KEY
      {{- end }}
      {{- if .Values.sentinel.sharding.enabled }}
      - name: SHARD_COUNT
        value: {{ .Values.sentinel.sharding.shardCount | quote }}
      {{- else }}
      - name: SHARD_INDEX
        value: "0"
      - name: SHARD_COUNT
        value: "1"
      {{- end }}
    {{- if .Values.sentinel.sharding.enabled }}
    # SHARD_INDEX comes from the StatefulSet ordinal: sentinel-2 owns shard 2. A Deployment cannot
    # do this — its pods are interchangeable and their names are random — which is the whole reason
    # sharding switches the workload kind.
    command: ["/bin/sh", "-c"]
    args:
      - |
        export SHARD_INDEX="${HOSTNAME##*-}"
        echo "starting shard ${SHARD_INDEX} of ${SHARD_COUNT}"
        exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar
    {{- end }}
    volumeMounts:
      - name: config
        mountPath: /app/config
        readOnly: true
    # Three probes, three different questions.
    #
    # startup:   "has it finished booting?" Flyway plus context refresh takes a while on a cold
    #            cluster, and this is what stops liveness killing it mid-migration. It probes
    #            liveness, not readiness, on purpose: readiness depends on Prometheus being
    #            reachable, and a startupProbe that waits on a dependency turns a slow dependency
    #            into a CrashLoopBackOff.
    # liveness:  "is the process wedged?" Deliberately narrow — livenessState only. If it also
    #            checked Prometheus, a Prometheus outage would restart every replica at once.
    # readiness: "should traffic come here?" Backed by the custom indicators: no Kafka partition
    #            assignment or an unreachable metrics source means unready, because a pod in that
    #            state serves requests while doing none of the actual work.
    startupProbe:
      httpGet:
        path: /actuator/health/liveness
        port: http
      periodSeconds: {{ .Values.sentinel.probes.startup.periodSeconds }}
      failureThreshold: {{ .Values.sentinel.probes.startup.failureThreshold }}
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: http
      periodSeconds: {{ .Values.sentinel.probes.liveness.periodSeconds }}
      failureThreshold: {{ .Values.sentinel.probes.liveness.failureThreshold }}
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: http
      periodSeconds: {{ .Values.sentinel.probes.readiness.periodSeconds }}
      failureThreshold: {{ .Values.sentinel.probes.readiness.failureThreshold }}
    lifecycle:
      preStop:
        exec:
          # Endpoint removal and SIGTERM are dispatched concurrently, not in order. Without this
          # pause the pod can stop accepting connections before kube-proxy has finished removing it
          # from the Service, and a slice of requests hits a closing listener. Sleeping here costs
          # five seconds of an already-graceful shutdown and removes the race entirely.
          command: ["sleep", "{{ .Values.sentinel.preStopSleepSeconds }}"]
    resources:
      {{- toYaml .Values.sentinel.resources | nindent 6 }}
volumes:
  - name: config
    configMap:
      name: {{ include "sentinel.fullname" . }}-config
{{- end -}}
