# Patches to the vendored llama.cpp

`llama.cpp` is a submodule pinned to the commit this app was built and measured against. Nothing
here is needed for the default build; both patches concern the optional Vulkan backend.

## 0001-vulkan-android-dynamic-dispatch.patch

`ggml-vulkan.cpp` calls `vkGetPhysicalDeviceFeatures2` directly. That links on a desktop Vulkan
loader, but **Android deliberately does not export Vulkan 1.1+ entry points from `libvulkan.so`** -
at any API level, checked against 24, 26, 28 and 29 - so the link fails with
`undefined symbol: vkGetPhysicalDeviceFeatures2`. The correct route on Android is
`vkGetInstanceProcAddr`, which is exactly what the dynamic dispatcher the file already builds
(`VULKAN_HPP_DEFAULT_DISPATCHER`) does. The patch routes the three call sites through it.

Apply with:

    git -C app/src/main/cpp/llama.cpp apply ../../../../patches/0001-vulkan-android-dynamic-dispatch.patch

Only needed when building with `-PvulkanBackend`.
