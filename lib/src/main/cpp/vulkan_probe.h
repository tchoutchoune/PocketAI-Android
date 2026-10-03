#pragma once
#include <string>
#include <sstream>
#include <iomanip>
#if POCKETAI_VULKAN
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#endif

namespace pocketai {
struct VulkanProbe {
    std::string name;
    std::string fingerprint;
    bool adreno840 = false;
};

inline VulkanProbe probe_vulkan() {
    VulkanProbe result;
#if POCKETAI_VULKAN
    void *library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return result;
    const auto proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
    const auto create = proc ? reinterpret_cast<PFN_vkCreateInstance>(proc(VK_NULL_HANDLE, "vkCreateInstance")) : nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "PocketAI capability probe";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    if (create && create(&info, nullptr, &instance) == VK_SUCCESS) {
        const auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(proc(instance, "vkDestroyInstance"));
        const auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(proc(instance, "vkEnumeratePhysicalDevices"));
        const auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(proc(instance, "vkGetPhysicalDeviceProperties"));
        const auto properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(proc(instance, "vkGetPhysicalDeviceProperties2"));
        uint32_t count = 0;
        if (enumerate && properties && enumerate(instance, &count, nullptr) == VK_SUCCESS && count > 0 && count <= 16) {
            VkPhysicalDevice devices[16]{};
            if (enumerate(instance, &count, devices) == VK_SUCCESS) for (uint32_t i = 0; i < count; ++i) {
                VkPhysicalDeviceProperties p{};
                properties(devices[i], &p);
                if (p.deviceType != VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU && p.deviceType != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) continue;
                result.name = p.deviceName;
                result.adreno840 = p.vendorID == 0x5143 && result.name.find("Adreno") != std::string::npos && result.name.find("840") != std::string::npos;
                std::ostringstream key;
                key << result.name << " vendor=" << p.vendorID << " device=" << p.deviceID << " api=" << p.apiVersion << " driver=" << p.driverVersion;
                if (properties2 && p.apiVersion >= VK_API_VERSION_1_2) {
                    VkPhysicalDeviceDriverProperties driver{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
                    VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
                    p2.pNext = &driver;
                    properties2(devices[i], &p2);
                    key << " " << driver.driverName << " " << driver.driverInfo;
                }
                key << " cache=";
                for (auto byte : p.pipelineCacheUUID) key << std::hex << std::setw(2) << std::setfill('0') << int(byte);
                result.fingerprint = key.str();
                break;
            }
        }
        if (destroy) destroy(instance, nullptr);
    }
    dlclose(library);
#endif
    return result;
}
}
