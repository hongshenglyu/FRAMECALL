# ACL1332诊断与实际验收

原轮1332仅有退出码、无原始stderr，不能声称唯一根因已被证明。本轮不复用旧目录；动态读取系统SID，使用icacls支持的 *SID 显式语法。实际命令、时间、退出码及脱敏输出见state-R1.json的commandLog。

本轮icacls退出0；备份根DACL受保护，只允许当前用户和SYSTEM；实际子文件继承两条允许规则；新建测试文件读写及删除均成功。未以另一普通用户身份登录测试，管理员/系统特权不在保证范围。

首轮DACL查询脚本退出1，后续独立查询及实际子文件验证成功，失败没有隐去。窗口首轮UTF8读取错误和随后隐藏窗口故障均已修正；确认结果以本机提交JSON为准。

ACL关口：PASS
旧目录：<PROJECT_PARENT>/FRAMECALL-backups/20261022-6099ccb118 (不复用、不修改、不删除)；未修复、删除、覆盖或复用。
详细DACL与子文件检查：
```json
{
  "ReadWritePassed": true,
  "DeleteOwnProbePassed": true,
  "DACL": [
    {
      "Protected": true,
      "AllowRules": 2,
      "UnexpectedAllowRules": 0,
      "InheritedRules": 0
    },
    {
      "Protected": false,
      "AllowRules": 2,
      "UnexpectedAllowRules": 0,
      "InheritedRules": 2
    }
  ],
  "VerificationExitCode": 0,
  "PreviousInspectionExitCode": 1,
  "PreviousInspectionLimitation": "首轮DACL查询脚本失败，未保留错误正文；此后独立查询与本次实际子文件查询成功，不伪造首轮成功",
  "Scope": "仅核验实际DACL；未以其他普通用户身份登录测试，也不限制管理员系统特权"
}
```