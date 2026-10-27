# deploy/

部署制品模板（语言无关，与 `proto/` 同为语言无关顶层目录）：Docker 镜像 Dockerfile 在仓库根（既有先例），VM 安装包与 Helm chart 模板在本目录。

| 项 | 说明 | 文档 |
|---|---|---|
| `vm/` | VM 标准安装包模板（bin/app.sh + systemd unit + 配置模板），`java/scripts/build-dist.sh` 组装出 tar.gz | [docs/deploy/vm.md](../docs/deploy/vm.md) |
| `helm/` | K8s Helm chart（v1.0 phase-4 落地） | — |
