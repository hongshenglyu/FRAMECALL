# Analysis Run v1 完整合同草案

2026-10-24。本文件仅为拟议设计/静态审计，未实施、未部署、未运行合同测试。所有HTTP预期均属于未来v1；现有行为见调用链及差距矩阵。

## 生效与定义

此文本拟作为未来docs/contracts/analysis-run-v1.md的内容；当前只在任务目录审阅。MUST表示未来v1验收要求，均未在现行实现生效。分析对象是接触候选，不是自动犯规判罚。任务技术成功、流水线来源真实性、候选数量是三种不同概念。现行AI默认环境开关和现行响应不会因本草案自动变化。

## 字段与责任

新公共DTO在旧字段之外增加analysisRun对象；以下字段均位于该对象（内部请求/结果用同一对象），避免覆盖旧响应。sourceModel等字段不得只写营销名称。

| 字段 | 定义/必填条件 | 生产与持久化责任 | 向后兼容 |
|---|---|---|---|
| contractVersion | 固定analysis-run-v1；新协商请求/响应必填 | 后端协商，AI验证；TaskAttempt保存 | 旧客户端无此字段走legacy，来源unknown |
| executionMode | real或mock；新attempt必填，创建后不可变 | 调用者显式请求，后端权限/功能开关校验；AI复核；Attempt持久化 | 缺失不能推断real；mock需显式演示/测试授权 |
| sourceModel | 组件数组：stage、kind、implementation、version、artifactId；real各核心阶段必填 | AI报告实际实现和版本；后端验证并保存来源清单 | 规则kind=rule、模拟kind=mock、占位kind=placeholder；禁止冒名ByteTrack |
| attemptId | 后端生成UUID，每次实际业务尝试唯一，所有内部消息必带 | 后端Attempt表与任务currentAttemptId；事件引用attempt | 旧taskId保留；旧attempt为空，不伪造历史run |
| status | PENDING/QUEUED/RUNNING/CANCEL_REQUESTED/CANCELLED/SUCCEEDED/FAILED | 唯一后端状态服务以CAS短事务写Attempt和Task摘要 | 保留CANCELLED拼写；旧UI不可被突然注入新枚举 |
| error | null或{code,stage,category,retryable,message}；FAILED必填 | AI提供分类事实；后端用确定性码表校验和脱敏后保存 | 同步生成脱敏errorMessage兼容旧UI；不传堆栈/秘密/绝对路径 |
| videoTimebase | 必含method、timeBase有理数、startPts、sourceFps可空、VFR标志、duration、宽高、采样映射 | 可信解码层探测，AI传播；后端保存视频级版本和attempt快照 | 旧时间值原样保存并标unknown；real不接受unknown时间基准 |
| resultProvenance | stage完成清单、coverage、evidenceOrigin及媒体manifest；real成功必填 | AI构造；后端独立验证媒体/输入/attempt关联后持久化 | detector成功不等于全链真实；旧记录legacy_unknown |
| stateVersion/callbackSeq | 后端状态CAS版本；AI每attempt严格递增回调序号 | 后端保存状态版本和lastSeq/消息摘要，拒绝迟到更新 | 不能用系统时钟排序回调 |
| inputVideoId/producedAt | 不可变视频修订ID；服务器UTC产物时间 | 后端选择已授权视频；AI产物UTC，仅审计不用来排序 | 复用videoId语义，加修订引用；不暴露本机路径 |

sourceModel组件stage至少detector/tracker/scorer/media，规则实现有独立版本。detector需记录模型artifactId及本地受控模型版本，不复制权重或秘密哈希。tracker标明真正关联实现，adapter_pending绝非真实完成。resultProvenance引用输入视频修订、attempt、声明分析区间及实际覆盖、阶段完成/来源、媒体manifest。派生媒体摘要仅计算非秘密研究产物，在后续授权实现阶段生成，不能用配置秘密摘要充当来源证明。

## Real/Mock确定性规则

后端准入负责选择executionMode，用户不可通过任意上传JSON指定内部callbackUrl、输出目录、模型路径或真实状态。新v1缺失mode返回422 MODE_REQUIRED；未授权mock返回403。AI进程AI_ANALYZER_MODE/AI_TRACKER_MODE仅作为能力/旧模式配置，不能覆盖已接受请求；请求real但工作节点不可执行real，明确失败，不改为mock。AI_FOUL_RULE_MODE默认track-rule-v1、AI_POSE_MODE默认local-rule-v1；真姿态不是real前置，代理若参与候选必须显式披露，不能把代理称为真实姿态。

Real成功必须：输入可读且身份匹配；时间基准可信；声明全输入区间已按合同处理；真实检测和真正ByteTrack阶段执行完毕；规则评分/候选合并完成；全部候选的源视频抽帧与可解码视频片段完整。任何Mock/placeholder/legacy_unknown核心产物或不完整覆盖均不能SUCCEEDED。当前maxFrames=12造成的提前终止不能冒充全输入处理完成，须FAILED COVERAGE_INCOMPLETE；采样检测本身允许，但需明确实际源帧/PTS映射。

完整流水线确实执行且0候选，返回SUCCEEDED、events=[]、error=null；没有检测到人可以合法产出空轨迹，仍要证明阶段正常执行。缺轨迹、时间错误、解码失败或半途停止不能伪装零候选。0候选不要求虚构事件媒体，EVIDENCE阶段记completed/no_candidates；coverage等视频级来源仍必填。

Mock显式成功可返回合成事件，所有组件/媒体标mock或illustration，API/UI/PDF水印和导出元信息带模式。正式评价入口采用白名单：mode=real、status=SUCCEEDED、来源完整且视频/比赛划分版本已冻结；未知/Mock/Smoke默认拒绝422 PROVENANCE_NOT_ELIGIBLE，独立fixture评估才允许mock。人工复核结论不能把mock模型来源洗成real。

## 状态与写入

完整转换、幂等和异常时序见同包状态机文档，属于本规范的规范性附件。保留CANCELLED，QUEUED为新增技术状态（当前仅stage）；仅后端状态服务写技术状态，AI回调只能报告进度/结果事实。所有状态变更以(taskId,currentAttemptId,stateVersion,expectedStatus)作CAS守卫，事件/最终清单与SUCCEEDED同一短事务提交。AI长计算及HTTP调用都在数据库事务外。

任务创建与持久派发记录在短事务提交，后台工作器经独立代理调用；派发/outbox选型OPEN，由01-05确认，但不得以同类@Async自调用替代可证明异步。每attempt独立输出目录与事件集合，旧attempt及人工复核不可先删除。retry只允许FAILED/CANCELLED且旧worker停止或lease已被围栏失效；SUCCEEDED重新分析需新任务/显式新版本，不能盲目删除成功数据。

## 错误与HTTP

公共v1创建/重试接受为202；GET状态200可带FAILED，不是业务分析成功。输入/模式/类型错误422，未认证401，越权403，非活动attempt/终态冲突409，删除墓碑410，服务过载503。保持旧路由，旧协商返回形状/HTTP行为先保持；不同协商不可静默混用。内部AI完成结果200必须是真实成功或明确mock成功；AI失败用非2xx及error信封，后端即使收到不一致200也必须验证后置条件再落库。

非重试码：MODEL_NOT_FOUND、DEPENDENCY_MISSING、VIDEO_UNREADABLE、TIMEBASE_INVALID、TRACKER_NOT_REAL、EVIDENCE_INVALID、COVERAGE_INCOMPLETE、PROVENANCE_NOT_ELIGIBLE。暂态码：WORKER_UNAVAILABLE/HTTP503/NETWORK_TIMEOUT可由确定性策略有限重试；默认最多3次业务尝试、两次间隔1秒和2秒为拟议上限，部署可收紧。未知是否执行的网络超时先按同attempt幂等ID查询/对账或等待lease围栏，不能直接创建并行新attempt；AI同attempt重复执行请求返回缓存终态或202处理中，绝不重算两次。新attempt只在前一终态或可证实围栏后产生。具体对账查询端点尚未实现，实施前由01-06补定并测试；无对账能力时保守失败等待人工，不无限重试。重试分类、路由、状态码和退避由确定性代码执行，模型无决策权。

## 内部鉴权与路径安全

内部进度必须校验服务凭证、作用域、taskId/attemptId、当前关联、输入修订及序号；凭证不进入普通客户端DTO、日志/PDF或结果JSON。callback目标由受控配置构造/注册，禁止客户端任意URL、重定向或DNS绕过白名单；不同服务身份分别限权。AI入站执行/取消同样要鉴权，不能只保护返回回调。公开查询/取消/重试/删除/事件/复核/PDF/文件均在后端执行owner/assigned/admin策略；授权在提供资源存在信息前判断。

新媒体引用用opaque assetId；服务内部将ID解析为受限根内路径，真实路径/符号链接/junction解析后必须仍在允许根且属于该视频/attempt。普通用户不得指定任意videoPath/outputDir。下载鉴权通过任务关联，不向浏览器提供本机绝对路径；旧路径参数仅作迁移适配并做相同权限判断。无网络访问/路径攻击已在本轮运行的主张。

## 时间与媒体

同包视频时基文档是本规范的规范性附件：PTS有理数是优先基准，禁止固定25fps。真实mp4/jpg必须可解码且绑定源视频/时间窗；示意图/文本片段永不等同real evidence。所有Real终态及正式评价均校验时间、覆盖和媒体来源。

## 迁移约束与OPEN

迁移顺序为可选字段/版本协商→后端校验/持久化与attempt状态→AI显式real/mock→前端/PDF/导出标签与评价过滤→历史版本化。前端过滤就绪前不得开放新real结果作为正式研究输出。现有登录、复核和旧报告仍可读，旧来源为legacy_unknown并排除正式研究。详细实施拆分见兼容迁移文档，不执行SQL或迁移。队列/锁实现、回调凭证细化、具体解码器及参数协议、对账端点仍待审查，不假定任何方案已部署。

## 合成JSON示例及版本协商

所有ID/时间/实现版本均为合成说明，绝不证明当前存在对应模型/视频/媒体。公共v1使用X-Analysis-Contract: analysis-run-v1协商，后端与AI必须回显contractVersion并验证新字段；不支持v1应拒绝，不静默忽略后仍标real。旧客户端无协商走legacy，旧API字段保持；新字段缺失校验与旧兼容必须显式分流。内部路径示例只由可信服务从assetId解析并校验根目录，客户端不能提交这些字段。内部凭证不放JSON示例。

请求、进度、报告和运行中状态采用明确projection：尚未产出的sourceModel/resultProvenance可空，producedAt可空；SUCCEEDED的完整run对象才按所有必填约束验证。事件以analysisRunRef关联已持久化完整run，不能仅凭事件自己声明origin就通过。上述空值豁免不适用于Real成功结果。

### 公共创建请求的multipart元数据

```json
{
  "sensitivity": "BALANCED",
  "analysisRun": {
    "contractVersion": "analysis-run-v1",
    "executionMode": "real"
  }
}
```

### 公共异步状态响应的投影

```json
{
  "id": 101,
  "status": "RUNNING",
  "stage": "TRACKING",
  "progressPercent": 45,
  "errorMessage": null,
  "analysisRun": {
    "contractVersion": "analysis-run-v1",
    "executionMode": "real",
    "attemptId": "00000000-0000-4000-8000-000000000001",
    "status": "RUNNING",
    "error": null,
    "stateVersion": 3,
    "inputVideoId": "synthetic-video-revision-1",
    "producedAt": null,
    "sourceModel": [],
    "videoTimebase": {
      "method": "pts",
      "streamIndex": 0,
      "timeBase": {
        "num": 1,
        "den": 1000
      },
      "startPts": 10000,
      "sourceFps": null,
      "isVfr": true,
      "durationMs": 10000,
      "durationOrigin": "decoded_presentation_timeline",
      "width": 1920,
      "height": 1080,
      "rotation": 0,
      "sampling": {
        "requestedStride": 1,
        "mappingAssetId": "synthetic-frame-pts-map",
        "mappingVersion": 1
      }
    },
    "resultProvenance": null
  }
}
```

### 可信后端到AI执行请求

```json
{
  "taskId": 101,
  "videoPath": "uploads/synthetic-video.mp4",
  "outputDir": "tasks/synthetic-attempt",
  "sensitivity": "BALANCED",
  "enabledEvents": [
    "FOUL"
  ],
  "callbackRegistrationId": "synthetic-trusted-backend",
  "analysisRun": {
    "contractVersion": "analysis-run-v1",
    "executionMode": "real",
    "attemptId": "00000000-0000-4000-8000-000000000001",
    "inputVideoId": "synthetic-video-revision-1"
  }
}
```

### 完整Real的合法零候选AI响应

```json
{
  "taskId": 101,
  "status": "SUCCEEDED",
  "stage": "COMPLETED",
  "progressPercent": 100,
  "resultJsonPath": "tasks/synthetic-attempt/result.json",
  "events": [],
  "metrics": {},
  "errorMessage": null,
  "analysisRun": {
    "contractVersion": "analysis-run-v1",
    "executionMode": "real",
    "attemptId": "00000000-0000-4000-8000-000000000001",
    "status": "SUCCEEDED",
    "error": null,
    "stateVersion": 5,
    "inputVideoId": "synthetic-video-revision-1",
    "producedAt": "2026-10-24T00:00:00Z",
    "sourceModel": [
      {
        "stage": "detector",
        "kind": "learned",
        "implementation": "YOLOv8",
        "version": "synthetic-example-1",
        "artifactId": "synthetic-artifact-1"
      },
      {
        "stage": "tracker",
        "kind": "rule",
        "implementation": "ByteTrack-association",
        "version": "synthetic-example-1",
        "artifactId": null
      },
      {
        "stage": "scorer",
        "kind": "rule",
        "implementation": "contact-rules",
        "version": "synthetic-example-1",
        "artifactId": null
      },
      {
        "stage": "media",
        "kind": "rule",
        "implementation": "source-video-extractor",
        "version": "synthetic-example-1",
        "artifactId": null
      }
    ],
    "videoTimebase": {
      "method": "pts",
      "streamIndex": 0,
      "timeBase": {
        "num": 1,
        "den": 1000
      },
      "startPts": 10000,
      "sourceFps": null,
      "isVfr": true,
      "durationMs": 10000,
      "durationOrigin": "decoded_presentation_timeline",
      "width": 1920,
      "height": 1080,
      "rotation": 0,
      "sampling": {
        "requestedStride": 1,
        "mappingAssetId": "synthetic-frame-pts-map",
        "mappingVersion": 1
      }
    },
    "resultProvenance": {
      "inputVideoRevision": "synthetic-video-revision-1",
      "attemptId": "00000000-0000-4000-8000-000000000001",
      "stages": {
        "validation": "completed",
        "detector": "completed",
        "tracker": "completed",
        "scorer": "completed",
        "evidence": "completed"
      },
      "coverage": {
        "declaredRangeMs": [
          0,
          10000
        ],
        "processedRangeMs": [
          0,
          10000
        ],
        "complete": true
      },
      "evidenceOrigin": "source_video",
      "mediaManifest": []
    }
  }
}
```

### 进度回调（凭证仅在受控请求头设置）

```json
{
  "taskId": 101,
  "stage": "TRACKING",
  "progressPercent": 45,
  "message": "synthetic progress",
  "analysisRun": {
    "contractVersion": "analysis-run-v1",
    "attemptId": "00000000-0000-4000-8000-000000000001",
    "callbackSeq": 3,
    "inputVideoId": "synthetic-video-revision-1"
  }
}
```

### 独立的非零候选事件示例

```json
{
  "id": 201,
  "externalId": "synthetic-candidate-1",
  "taskId": 101,
  "eventType": "FOUL",
  "riskLevel": "UNCERTAIN",
  "timestampMs": 2000,
  "windowStartMs": 1000,
  "windowEndMs": 3000,
  "confidence": 0.5,
  "summary": "合成接触候选，不是犯规判定",
  "involvedTrackIds": [
    1,
    2
  ],
  "keyframePath": "",
  "clipPath": "",
  "analysisRunRef": "00000000-0000-4000-8000-000000000001",
  "evidence": {
    "keyframeAssetId": "synthetic-frame-1",
    "clipAssetId": "synthetic-clip-1",
    "origin": "source_video",
    "inputVideoRevision": "synthetic-video-revision-1",
    "keyframeSourcePts": 12000,
    "timeBase": {
      "num": 1,
      "den": 1000
    },
    "requestedRangeMs": [
      1000,
      3000
    ],
    "actualRangeMs": [
      1000,
      3000
    ],
    "validation": "decoded_and_source_aligned"
  }
}
```

### 演示PDF/导出报告元信息投影

```json
{
  "taskId": 102,
  "analysisRun": {
    "contractVersion": "analysis-run-v1",
    "attemptId": "00000000-0000-4000-8000-000000000002",
    "executionMode": "mock",
    "status": "SUCCEEDED"
  },
  "reportOrigin": "mock",
  "watermark": "演示产物，不用于真实实验",
  "formalEvaluationEligible": false,
  "exclusionReason": "MOCK_SOURCE"
}
```

零候选响应与后面的非零候选示例属于两个独立合成场景，不可把非零事件并入空事件响应。正式媒体manifest需与事件资产逐项对应，服务器不能信任客户端写validation字符串。旧keyframePath/clipPath空值示例表示新资产ID投影需由兼容适配器按鉴权下载路由提供，现有图片UI未接通前禁止直接发布该投影。

PENDING/QUEUED未派发的取消可由调度器确认派发撤销且未被worker认领，作为停止确认；不需要伪造AI停止ACK。RUNNING取消则需真实停止确认或超时后FAILED并围栏旧worker。
