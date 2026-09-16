package com.linkn.screenintake.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 分类 → 小图标的对应表，就是给列表里每一笔记录一个能一眼看出是什么的图标，
 * 不用很花哨，都是系统自带的常见图标。分类名对不上（比如模型识别之前老数据里的
 * 自由文本分类）就兜底给一个通用的「其他」图标，不会崩、也不会显示空白。
 */
object CategoryIcons {

    private val map: Map<String, ImageVector> = mapOf(
        // 支出
        "充电" to Icons.Filled.BatteryChargingFull,
        "餐饮" to Icons.Filled.Restaurant,
        "过路费" to Icons.Filled.Toll,
        "因公差旅" to Icons.Filled.BusinessCenter,
        "停车费" to Icons.Filled.LocalParking,
        "应酬社交" to Icons.Filled.LocalBar,
        "通讯" to Icons.Filled.Call,
        "小甜蜜" to Icons.Filled.Favorite,
        "汽车" to Icons.Filled.DirectionsCar,
        "日用消费" to Icons.Filled.ShoppingCart,
        "运动" to Icons.Filled.DirectionsBike,
        "娱乐" to Icons.Filled.Theaters,
        "住房" to Icons.Filled.Home,
        "医疗" to Icons.Filled.LocalHospital,
        "保险" to Icons.Filled.Security,
        "数码" to Icons.Filled.Devices,
        "旅行" to Icons.Filled.Flight,
        "学习" to Icons.Filled.School,
        "亲情" to Icons.Filled.FavoriteBorder,
        "装修" to Icons.Filled.Build,
        // 收入
        "工资" to Icons.Filled.AttachMoney,
        "奖金" to Icons.Filled.EmojiEvents,
        "兼职收入" to Icons.Filled.Work,
        "理财收益" to Icons.Filled.TrendingUp,
        "报销" to Icons.Filled.Receipt,
        "红包礼金" to Icons.Filled.CardGiftcard,
        "打麻将" to Icons.Filled.Casino
    )

    fun iconFor(category: String?): ImageVector = map[category] ?: Icons.Filled.Category
}
