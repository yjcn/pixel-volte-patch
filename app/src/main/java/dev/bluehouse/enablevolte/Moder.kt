package dev.bluehouse.enablevolte

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.IBinder
import android.os.IInterface
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.internal.telephony.ICarrierConfigLoader
import com.android.internal.telephony.IPhoneSubInfo
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

private const val TAG = "CarrierModer"

object InterfaceCache {
    val cache = HashMap<String, IInterface>()
}

fun callGetConfigForSubId(obj: Any, subId: Int): PersistableBundle {
    val method = obj.javaClass.getMethod("getConfigForSubId", Int::class.javaPrimitiveType)
    return method.invoke(obj, subId) as PersistableBundle
}

fun getCarrierConfigWithShizuku(subId: Int): PersistableBundle? {
    try {
        val binder = SystemServiceHelper.getSystemService("carrier_config")
        val carrierConfigLoader = ICarrierConfigLoader.Stub.asInterface(
            ShizukuBinderWrapper(binder),
        )
        return carrierConfigLoader.getConfigForSubId(subId)
    } catch (e: Exception) {
        Log.e("CarrierConfig", "Shizuku获取配置失败", e)
        return null
    }
}

fun callOverrideConfig(obj: Any, subscriptionId: Int, overrideValues: PersistableBundle?) {
    try {
        val method = obj.javaClass.getMethod("overrideConfig", Int::class.javaPrimitiveType, PersistableBundle::class.java)
        method.invoke(obj, subscriptionId, overrideValues)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

open class Moder {
    val KEY_IMS_USER_AGENT = "ims.ims_user_agent_string"

    protected inline fun <reified T : IInterface>loadCachedInterface(interfaceLoader: () -> T): T {
        InterfaceCache.cache[T::class.java.name]?.let {
            return it as T
        } ?: run {
            val i = interfaceLoader()
            InterfaceCache.cache[T::class.java.name] = i
            return i
        }
    }

    // 使用反射获取ServiceManager，明确返回IBinder类型
    private fun getServiceBinder(serviceName: String): IBinder {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, serviceName)

            // 添加明确的类型转换
            if (binder != null) {
                return binder as IBinder
            } else {
                throw RuntimeException("Service $serviceName not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get service: $serviceName", e)
            throw RuntimeException("Failed to get service: $serviceName", e)
        }
    }

    // 通过服务名称获取系统服务
    protected val carrierConfigLoader: ICarrierConfigLoader
        get() = loadCachedInterface {
            ICarrierConfigLoader.Stub.asInterface(
                ShizukuBinderWrapper(getServiceBinder("carrier_config")),
            )
        }

    protected val telephony: ITelephony
        get() = loadCachedInterface {
            ITelephony.Stub.asInterface(
                ShizukuBinderWrapper(getServiceBinder("phone")),
            )
        }

    protected val phoneSubInfo: IPhoneSubInfo
        get() = loadCachedInterface {
            IPhoneSubInfo.Stub.asInterface(
                ShizukuBinderWrapper(getServiceBinder("iphonesubinfo")),
            )
        }

    protected val sub: ISub
        get() = loadCachedInterface {
            ISub.Stub.asInterface(
                ShizukuBinderWrapper(getServiceBinder("isub")),
            )
        }
}

class CarrierModer(private val context: Context) : Moder() {
    fun getActiveSubscriptionInfoForSimSlotIndex(index: Int): SubscriptionInfo? {
        val sub = this.loadCachedInterface { sub }
        return sub.getActiveSubscriptionInfoForSimSlotIndex(index, null, null)
    }

    val subscriptions: List<SubscriptionInfo>
        get() {
            val sub = this.loadCachedInterface { sub }
            return try {
                sub.getActiveSubscriptionInfoList(null, null)
            } catch (e: NoSuchMethodError) {
                // FIXME: lift up reflect as soon as official source code releases
                val getActiveSubscriptionInfoListMethod = sub.javaClass.getMethod(
                    "getActiveSubscriptionInfoList",
                    String::class.java,
                    // String::class.java,
                    // Boolean::class.java,
                )
                // (getActiveSubscriptionInfoListMethod.invoke(sub, null, null, false) as List<SubscriptionInfo>)
                (getActiveSubscriptionInfoListMethod.invoke(sub, null) as List<SubscriptionInfo>)
            }
        }

    val defaultSubId: Int
        get() {
            val sub = this.loadCachedInterface { sub }
            return sub.defaultSubId
        }

    val deviceSupportsIMS: Boolean
        get() {
            val res = Resources.getSystem()
            val volteConfigId = res.getIdentifier("config_device_volte_available", "bool", "android")
            return res.getBoolean(volteConfigId)
        }
}

class SubscriptionModer(val subscriptionId: Int) : Moder() {
    private fun publishBundle(fn: (PersistableBundle) -> Unit) {
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }
        val overrideBundle = PersistableBundle()
        fn(overrideBundle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.overrideConfig(this.subscriptionId, overrideBundle, true)
        } else {
            callOverrideConfig(iCclInstance, this.subscriptionId, overrideBundle)
        }
    }
    fun updateCarrierConfig(key: String, value: Boolean) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putBoolean(key, value) }
    }

    fun updateCarrierConfig(key: String, value: String) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putString(key, value) }
    }

    fun updateCarrierConfig(key: String, value: Int) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putInt(key, value) }
    }
    fun updateCarrierConfig(key: String, value: Long) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putLong(key, value) }
    }

    fun updateCarrierConfig(key: String, value: IntArray) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putIntArray(key, value) }
    }

    fun updateCarrierConfig(key: String, value: BooleanArray) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putBooleanArray(key, value) }
    }

    fun updateCarrierConfig(key: String, value: Array<String>) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putStringArray(key, value) }
    }

    fun updateCarrierConfig(key: String, value: LongArray) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putLongArray(key, value) }
    }

    fun clearCarrierConfig() {
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.overrideConfig(this.subscriptionId, null, true)
        } else {
            callOverrideConfig(iCclInstance, this.subscriptionId, null)
        }
    }

    fun restartIMSRegistration() {
        val telephony = this.loadCachedInterface { telephony }
        val sub = this.loadCachedInterface { sub }
        telephony.resetIms(sub.getSlotIndex(this.subscriptionId))
    }

    fun getStringValue(key: String): String {
        Log.d(TAG, "Resolving string value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return ""
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } else {
            getCarrierConfigWithShizuku(subscriptionId)
        }
        return config.getString(key)
    }

    fun getBooleanValue(key: String): Boolean {
        Log.d(TAG, "Resolving boolean value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return false
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } else {
            getCarrierConfigWithShizuku(subscriptionId)
        }
        return config.getBoolean(key)
    }

    fun getIntValue(key: String): Int {
        Log.d(TAG, "Resolving integer value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return -1
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } else {
            getCarrierConfigWithShizuku(subscriptionId)
        }
        return config.getInt(key)
    }

    fun getLongValue(key: String): Long {
        Log.d(TAG, "Resolving long value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return -1
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } else {
            getCarrierConfigWithShizuku(subscriptionId)
        }
        return config.getLong(key)
    }

    fun getBooleanArrayValue(key: String): BooleanArray {
        Log.d(TAG, "Resolving boolean array value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return booleanArrayOf()
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } else {
            getCarrierConfigWithShizuku(subscriptionId)
        }
        return config.getBooleanArray(key)
    }

    fun getIntArrayValue(key: String): IntArray {
        Log.d(TAG, "Resolving integer value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return intArrayOf()
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } else {
            getCarrierConfigWithShizuku(subscriptionId)
        }
        return config.getIntArray(key)
    }

    fun getStringArrayValue(key: String): Array<String> {
        Log.d(TAG, "Resolving string array value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return arrayOf()
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } else {
            getCarrierConfigWithShizuku(subscriptionId)
        }
        return config.getStringArray(key)
    }
    fun getValue(key: String): Any? {
        Log.d(TAG, "Resolving value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return null
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } else {
            getCarrierConfigWithShizuku(subscriptionId)
        }
        return config.get(key)
    }

    val simSlotIndex: Int
        get() = this.loadCachedInterface { sub }.getSlotIndex(subscriptionId)

    val isVoLteConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)

    val isVoNrConfigEnabled: Boolean
        @RequiresApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_VONR_ENABLED_BOOL) &&
            this.getBooleanValue(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL)

    val isCrossSIMConfigEnabled: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL) &&
                    this.getBooleanValue(CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL)
            } else {
                false
            }
        }

    val isVoWifiConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)

    val isVoWifiWhileRoamingEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL)

    val showIMSinSIMInfo: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL)

    val allowAddingAPNs: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_ALLOW_ADDING_APNS_BOOL)

    val showVoWifiMode: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL)

    val showVoWifiRoamingMode: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL)

    val wfcSpnFormatIndex: Int
        get() = this.getIntValue(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT)

    val carrierName: String
        get() = this.loadCachedInterface { telephony }.getSubscriptionCarrierName(this.subscriptionId)

    val showVoWifiIcon: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL)

    val alwaysDataRATIcon: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL)

    val supportWfcWifiOnly: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL)

    val isVtConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL)

    val ssOverUtEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL)

    val ssOverCDMAEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SUPPORT_SS_OVER_CDMA_BOOL)

    val isShow4GForLteEnabled: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL)

    val isHideEnhancedDataIconEnabled: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL)

    val is4GPlusEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) &&
            this.getBooleanValue(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL) &&
            !this.getBooleanValue(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)

    val isNRConfigEnabled: Boolean
        @RequiresApi(Build.VERSION_CODES.S)
        get() = this.getIntArrayValue(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)
            .contentEquals(intArrayOf(1, 2))

    val userAgentConfig: String
        get() = this.getStringValue(KEY_IMS_USER_AGENT)

    val isIMSRegistered: Boolean
        get() {
            val telephony = this.loadCachedInterface { telephony }
            return telephony.isImsRegistered(this.subscriptionId)
        }
}
