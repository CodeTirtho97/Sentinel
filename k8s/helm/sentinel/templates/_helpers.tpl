{{/*
Chart name, overridable.
*/}}
{{- define "sentinel.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Resource name prefix. With the conventional `helm install sentinel ./k8s/helm/sentinel` this
collapses to just "sentinel", so the Services come out as sentinel-postgres, sentinel-redpanda and
so on rather than sentinel-sentinel-postgres.
*/}}
{{- define "sentinel.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "sentinel.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "sentinel.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Selector labels for a component. Call as (dict "root" $ "component" "postgres").
*/}}
{{- define "sentinel.selectorLabels" -}}
app.kubernetes.io/name: {{ include "sentinel.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
Pod template labels. Same argument as selectorLabels.

Exists because `sentinel.labels` and `sentinel.selectorLabels` both carry name and instance, so
emitting them together produced a mapping with duplicate keys — accepted by lenient parsers, an
error under strict decoding, and confusing in `kubectl get -o yaml` either way. This is the union,
computed once.
*/}}
{{- define "sentinel.podLabels" -}}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{ include "sentinel.selectorLabels" . }}
{{- end -}}

{{/*
Hostnames. Everything that needs to reach another component goes through these rather than
hardcoding a name, so a second release in the same namespace does not silently cross-wire.
*/}}
{{- define "sentinel.postgresHost" -}}{{ include "sentinel.fullname" . }}-postgres{{- end -}}
{{- define "sentinel.redpandaHost" -}}{{ include "sentinel.fullname" . }}-redpanda{{- end -}}
{{- define "sentinel.redisHost"    -}}{{ include "sentinel.fullname" . }}-redis{{- end -}}
{{- define "sentinel.prometheusHost" -}}{{ include "sentinel.fullname" . }}-prometheus{{- end -}}

{{/*
A fleet member's Service name. The Kubernetes name is prefixed; the SERVICE_NAME the app reports
itself as is not, because that string is the `service` metric label the recording rules aggregate
by and the key in the dependency graph. Renaming it would break correlation.
*/}}
{{- define "sentinel.fleetHost" -}}
{{- printf "%s-%s" (include "sentinel.fullname" .root) .name -}}
{{- end -}}
