{{/*
Copyright (c) 2026 IS4IT

SPDX-License-Identifier: EUPL-1.2
*/}}

{{- define "midgie.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "midgie.fullname" -}}
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

{{- define "midgie.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "midgie.labels" -}}
helm.sh/chart: {{ include "midgie.chart" . }}
{{ include "midgie.selectorLabels" . }}
app.kubernetes.io/version: {{ default .Chart.AppVersion .Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "midgie.selectorLabels" -}}
app.kubernetes.io/name: {{ include "midgie.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "midgie.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "midgie.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* The claim to mount: an existing one, or the one this chart creates. */}}
{{- define "midgie.claimName" -}}
{{- default (include "midgie.fullname" .) .Values.persistence.existingClaim -}}
{{- end -}}

{{/* The Secret holding the midPoint password: an existing one, or the one this chart creates. */}}
{{- define "midgie.passwordSecretName" -}}
{{- default (include "midgie.fullname" .) .Values.midpoint.existingSecret -}}
{{- end -}}
