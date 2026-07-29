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

{{/* Where the ssh Secret is mounted. $HOME in the image, so ssh finds it without being told. */}}
{{- define "midgie.sshDir" -}}
/home/midgie/.ssh
{{- end -}}

{{/*
How git should invoke ssh: at the key under the name it has in the Secret. ssh only auto-loads its own
default filenames (id_rsa, id_ecdsa, id_ed25519, …), so a Secret entry called anything else — 'deploy-key',
say — would be mounted and then never offered. IdentitiesOnly stops any other identity being tried first.
*/}}
{{- define "midgie.gitSshCommand" -}}
ssh -i {{ include "midgie.sshDir" . }}/{{ .Values.ssh.privateKeyKey }} -o IdentitiesOnly=yes
{{- if .Values.ssh.knownHostsKey }} -o UserKnownHostsFile={{ include "midgie.sshDir" . }}/{{ .Values.ssh.knownHostsKey }}{{ end }}
{{- if .Values.ssh.acceptNewHostKeys }} -o StrictHostKeyChecking=accept-new{{ end }}
{{- end -}}

{{/* The Secret holding the midPoint password: an existing one, or the one this chart creates. */}}
{{- define "midgie.passwordSecretName" -}}
{{- default (include "midgie.fullname" .) .Values.midpoint.existingSecret -}}
{{- end -}}
