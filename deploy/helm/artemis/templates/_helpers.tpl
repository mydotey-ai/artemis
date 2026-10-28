{{/* 展开名（惯例：release 名含 chart 名取 release 名，否则拼合）。
     截断 50 而非 63：后接组件后缀（最长 "-management" 10 字符）+ StatefulSet ordinal（"-0"）
     不得超过 DNS 标签 63，预算 = 63 - 10 - 2 */}}
{{- define "artemis.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 50 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 50 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 50 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* 通用标签 */}}
{{- define "artemis.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: artemis
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* 选择器标签（component = registry/discovery/management） */}}
{{- define "artemis.selectorLabels" -}}
app.kubernetes.io/name: artemis
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* 镜像名：<registry/><servicePrefix><service>:<tag> */}}
{{- define "artemis.image" -}}
{{- printf "%s%s%s:%s" .Values.image.registry .Values.image.servicePrefix .service .Values.image.tag -}}
{{- end -}}

{{- define "artemis.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "artemis.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* registry peer 端点列表（逗号分隔，discovery 上游 clusters 用） */}}
{{- define "artemis.registry.peerEndpoints" -}}
{{- $fullname := include "artemis.fullname" . -}}
{{- $peers := list -}}
{{- range $i, $e := until (int .Values.registry.replicas) -}}
{{- $peers = append $peers (printf "%s-registry-%d.%s-registry-peer.%s.svc:9093" $fullname $i $fullname $.Release.Namespace) -}}
{{- end -}}
{{- join "," $peers -}}
{{- end -}}

{{/* registry 三段式成员表（<id>@<peer>@<external>，逗号分隔）；membersOverride 显式优先 */}}
{{- define "artemis.registry.members" -}}
{{- if .Values.registry.membersOverride -}}
{{- .Values.registry.membersOverride -}}
{{- else -}}
{{- $fullname := include "artemis.fullname" . -}}
{{- $ext := printf "%s-registry.%s.svc:9090" $fullname .Release.Namespace -}}
{{- $members := list -}}
{{- range $i, $e := until (int .Values.registry.replicas) -}}
{{- $id := printf "%s-registry-%d" $fullname $i -}}
{{- $members = append $members (printf "%s@%s-registry-%d.%s-registry-peer.%s.svc:9093@%s" $id $fullname $i $fullname $.Release.Namespace $ext) -}}
{{- end -}}
{{- join "," $members -}}
{{- end -}}
{{- end -}}

{{/* 认证 env（ARTEMIS_<SVC>_AUTH_ENABLED + USERNAME/PASSWORD）。ENABLED 键必发：
     产品默认 registry/management=true、discovery=false，仅发凭证不发开关时
     「关」语义不生效（默认凭证仍在跑）、「开」语义不生效（端口仍匿名） */}}
{{- define "artemis.authEnv" -}}
{{- $prefix := printf "ARTEMIS_%s_AUTH" (upper .service) -}}
- name: {{ $prefix }}_ENABLED
  value: {{ .enabled | quote }}
{{- if .enabled }}
{{- if .auth.existingSecret }}
- name: {{ $prefix }}_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ .auth.existingSecret }}
      key: username
- name: {{ $prefix }}_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .auth.existingSecret }}
      key: password
{{- else }}
- name: {{ $prefix }}_USERNAME
  value: {{ .auth.username | quote }}
- name: {{ $prefix }}_PASSWORD
  value: {{ .auth.password | quote }}
{{- end }}
{{- end }}
{{- end -}}
