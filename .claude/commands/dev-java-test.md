# dev-java-test：Java 测试执行入口（会话侧）

调用 `java/scripts/dev-test.sh` 执行测试并汇总结果。参数可选 `unit` | `integration` | `all`（默认 `all`，全量 verify）——模式定义与形态↔模式映射的单一来源：当前 phase 设计文档「自动化测试入口」节（见 `docs/milestones/dev-state.md`）。

## 执行

```bash
java/scripts/dev-test.sh [unit|integration|all]
```

## 汇总要求

- 逐模块汇总测试结果：模块 / 用例数 / 通过 / 失败 / 跳过。
- 失败时：定位失败模块与用例，引用关键错误输出给出诊断结论（失败原因、疑似根因、建议修复方向）。
- **不擅自修改代码**：修复走用户指示或 /dev-java 工作流 ⑦（fix）。
