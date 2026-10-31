# GitHub 搜索与分享素材

本分支已将元信息文件同步为知识工作区与 Agent 的定位；这些文件不会因 Git 推送而自动修改线上 About。英文 Description 与 Topics 的完整设置分别保存在 [about.json](about.json)、[topics.json](topics.json)。Topics 同时覆盖使用场景、核心问题和实现技术。

在能访问 GitHub API 且具有此仓库管理权限的机器上，从仓库根目录运行：

```bash
bash scripts/apply-github-metadata.sh
```

脚本使用已有 GitHub CLI 身份，不读取或输出令牌；重复执行会设置相同内容。脚本先读取并保留已有 Topics，再合并新增标签；合并超过 GitHub 的 20 个标签上限时会停止，供人工筛选。此前云环境 `gh api repos/wangbf123/StudyFlow` 返回 Forbidden，**线上描述和 Topics 尚未更新**；Git 读取成功不能证明仓库 API 写权限可用。

README 已使用项目封面。GitHub 链接分享卡片使用独立的 Social preview 设置：在仓库 Settings → General → Social preview 上传 [现有封面](../../.github/assets/knowledge-cover.png)，按 GitHub 的预览调整裁切。该设置尚未执行，README 封面和 Social preview 是两个不同位置。

技术文章放在 [docs/articles](../articles/README.md)。文章稿件和社区摘要已完成，尚未向任何外部平台发布。发布时保留验证条件、代码链接和模型／基础设施边界。
