# Nexus API 调试台装饰图 v1

模式：内置 imagegen，`background-extraction`，透明背景。参考输入为用户提供的手机端视觉稿局部截图。

最终提示词：

> Use case: background-extraction. Asset type: transparent decorative PNG for Nexus mobile API playground header. Input image 1 is the exact reference/edit target: a small warm golden hand-drawn five-point star at lower-left and a soft pale sage watercolor botanical sprig on the right, gently extending upward and partially off the top/right crop. Isolate those two existing decorative motifs faithfully, preserving their relative position, soft watercolor texture, light pencil edges, muted gold and sage colors, delicacy, and generous empty space between them. Remove the entire white/off-white screenshot background to true alpha transparency; no solid white rectangle, no paper backdrop, no shadows, no text, no UI, no borders, no extra leaves or stars. Keep it usable over warm off-white page background. Output a clean transparent cutout with naturally fading semi-transparent botanical edges.

实际页面素材：[playground-decoration.png](../../../nexus-console/src/assets/playground-decoration.png)。
