/*
 * Zhihu++ - Free & Ad-Free Zhihu client for all platforms.
 * Copyright (C) 2024-2026, zly2006 <i@zly2006.me>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation (version 3 only).
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.github.zly2006.zhihu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.github.zly2006.zhihu.navigation.Account
import com.github.zly2006.zhihu.navigation.Article
import com.github.zly2006.zhihu.navigation.CollectionContent
import com.github.zly2006.zhihu.navigation.Collections
import com.github.zly2006.zhihu.navigation.History
import com.github.zly2006.zhihu.navigation.Notification
import com.github.zly2006.zhihu.navigation.Person
import com.github.zly2006.zhihu.navigation.Pin
import com.github.zly2006.zhihu.navigation.Question
import com.github.zly2006.zhihu.navigation.Search
import com.github.zly2006.zhihu.navigation.Topic

/*
 * 内容槽：让可搬运页面换宿主时保住页面组合。
 *
 * 大屏分栏与竖屏单栈的归属不同，横竖交界处必须把页面从一个返回栈搬到另一个返回栈，而 Navigation Compose 没有跨
 * 控制器迁移 entry 的 API，搬运只能是 pop + navigate，页面 entry 因此被重建。`rememberSaveable` 的 key 由组合
 * 位置决定，跨宿主存/取一定 miss，重建重叠期用框架 `SaveableStateHolder` 还会抛 “Key was used multiple times”；
 * 所以这里不存状态，而是把**同一棵组合**搬过去——值随组合移动，不经过任何 registry。
 *
 * 所有权是必须的：搬运期间新旧 entry 可能同时组合，必须只有一个渲染，否则状态会被后组合者接管。
 *
 * 另一条硬约束（实测）：Compose 依据**内容 lambda 的定义处**判断“要搬的是同一棵组合”，因此搬运两侧必须调用
 * 同一处定义的 render，宿主差异只能用值表达，详见 [ContentSlotHost] 的 KDoc。
 */

/** 内容离开返回栈后等待这么久再释放：跨过 pop → navigate 的间隙和退场动画。 */
internal const val CONTENT_SLOT_RELEASE_DELAY_MILLIS = 500L

/**
 * 内容身份：同一个内容在不同宿主之间搬运时保持不变。
 *
 * 只由“用户看到的是哪个内容”决定，不包含 entry id、控制器和所在栏位；字段语义与
 * `MainActivity.isSamePageAs` 的去重判断一致。拿不到身份的页面（主壳、编辑器、设置、登录等）返回 null，
 * 它们不会在返回栈之间搬运，继续走原来的渲染路径。
 */
internal fun NavBackStackEntry.contentIdentityOrNull(): String? = when {
    destination.hasRoute<Article>() -> routeOrNull<Article>()?.let { "article/${it.type.name}/${it.id}" }
    destination.hasRoute<Pin>() -> routeOrNull<Pin>()?.let { "pin/${it.id}" }
    destination.hasRoute<Question>() -> routeOrNull<Question>()?.let { "question/${it.questionId}" }
    destination.hasRoute<Person>() -> routeOrNull<Person>()?.let {
        "person/" + if (it.id != Person.EMPTY_ID) it.id else it.urlToken
    }
    destination.hasRoute<Topic>() -> routeOrNull<Topic>()?.let { "topic/${it.id}/${it.section}" }
    destination.hasRoute<Search>() -> routeOrNull<Search>()?.let {
        "search/${it.query}/${it.restrictedMemberHashId}"
    }
    destination.hasRoute<Collections>() -> routeOrNull<Collections>()?.let { "collections/${it.userToken}" }
    destination.hasRoute<CollectionContent>() -> routeOrNull<CollectionContent>()?.let { "collection/${it.collectionId}" }
    destination.hasRoute<History>() -> "history"
    destination.hasRoute<Notification.Entry>() -> routeOrNull<Notification.Entry>()?.let { "notification/${it.entryName}" }
    destination.hasRoute<Notification.Invitations>() -> "notification/invitations"
    destination.hasRoute<Notification>() -> "notification"
    destination.hasRoute<Account.RecommendSettings.BlockedFeedHistory>() -> "blocked_feed_history"
    else -> null
}

private inline fun <reified T : Any> NavBackStackEntry.routeOrNull(): T? = runCatching { toRoute<T>() }.getOrNull()

/**
 * 内容级 ViewModelStore 的持有者。
 *
 * 由主壳所在的生命周期所有者（Activity / 窗口）保留，因此配置变更重建组合后页面仍能从同一个 store 拿到原来的
 * ViewModel；内容真的离开返回栈时由 [ContentSlotRegistry.dropMissing] 逐个 clear，Activity 结束时整体释放。
 */
internal class ContentViewModelStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

/**
 * 一个内容的宿主槽：持有可搬运的组合、当前有权渲染它的 entry 和内容级 ViewModelStore。
 */
@Stable
internal class ContentSlot(
    val viewModelStore: ViewModelStore,
) {
    /** 有权渲染该内容的 entry id；null 表示还没有组合者接管。 */
    var ownerEntryId: String? by mutableStateOf(null)

    /**
     * 页面组合本身；搬运时连同其中 `remember` / `rememberSaveable` 的值一起移动。
     *
     * 参数固定为 (target, render)，其中 [render] 必须是唯一一处定义——Compose 判断“要搬的是同一棵组合”看的是
     * 内容 lambda 的定义处，而不是实例（实测：同一处定义的不同实例可以搬运，两侧各写一份 lambda 会重建页面状态）。
     */
    val movable: @Composable (target: Any?, render: @Composable (Any?) -> Unit) -> Unit =
        movableContentOf { target, render -> render(target) }
}

/**
 * 内容槽注册表：按内容身份持有槽，并裁决“谁渲染”。
 *
 * 清理必须看“两个返回栈里还有没有这个内容”，不能挂在槽的 dispose 上：隐藏一侧栏也会把整棵子树移出组合，但
 * entry 仍在返回栈里。搬运本身不会让 key 缺席（先建新 entry 再弹旧的，或在同一帧内完成），调用方仍要等一个
 * 沉降窗口再释放，以跨过 pop → navigate 的间隙。
 */
@Stable
internal class ContentSlotRegistry(
    private val viewModelStores: ContentViewModelStores,
    /**
     * owner entry 是否还在任一返回栈里。
     *
     * 必须同步读取当前返回栈（不能用会晚一帧的快照）：owner 被 pop 之后，正在组合的那个宿主应当立刻接管，
     * 否则该内容会空白到安全网下一次 claim；而搬运途中旧 entry 仍在栈里，不会因此被误判成"离开"。
     */
    val isEntryLive: (entryId: String) -> Boolean = { true },
) {
    private val slots = mutableMapOf<String, ContentSlot>()

    fun slotFor(key: String): ContentSlot = slots.getOrPut(key) { ContentSlot(viewModelStores.storeFor(key)) }

    /**
     * 把内容所有权交给当前页面。
     *
     * 必须在 `navigate` 返回后立刻调用：同帧切换所有权，旧宿主才会在这一次组合里就停止渲染，新宿主直接接管同一棵
     * 组合。没有内容身份的页面不做任何事。
     */
    fun claim(entry: NavBackStackEntry?) {
        val key = entry?.contentIdentityOrNull() ?: return
        slotFor(key).ownerEntryId = entry.id
    }

    /** 释放已经不在任何返回栈里的内容，连同它的 ViewModelStore。 */
    fun dropMissing(liveKeys: Set<String>) {
        slots.keys.toList().forEach { key ->
            if (key !in liveKeys) {
                slots.remove(key)
                viewModelStores.clear(key)
            }
        }
    }
}

private class ContentStoreOwner(
    private val store: ViewModelStore,
) : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore
        get() = store
}

/**
 * 渲染一个可能被搬运的页面。
 *
 * [render] 必须是**唯一一处定义**：Compose 判断“要搬的是同一棵组合”看的是内容 lambda 的定义处（composable lambda
 * key），两侧各写一份 lambda 时页面的 `remember` 会在搬运时重建（本机 JVM 实测：同一处定义的不同实例可以搬运，
 * 不同定义处不行）。因此同一个内容只写一处内容定义，宿主差异（哪个 entry、在哪一侧、是否可见）全部走 [target] 的值。
 *
 * target 与 render 用 `Any?` 而不是泛型参数：composable lambda 的实例是 `ComposableLambdaImpl`，不能强转成泛型的
 * `Function3`（运行时会 ClassCastException）。调用方在 render 里把 target 转回自己的类型即可。
 *
 * 只有 owner 真正渲染，其余宿主渲染空：搬运时所有权已在 `navigate` 之后同帧切换，因此同一帧只有一侧持有这棵组合。
 * 页面读到的 `LocalViewModelStoreOwner` 被换成内容级 store，旧 entry 销毁不会 clear 掉正在使用的 ViewModel。
 */
@Composable
internal fun ContentSlotHost(
    slots: ContentSlotRegistry,
    entry: NavBackStackEntry,
    target: Any?,
    render: @Composable (Any?) -> Unit,
) {
    val key = entry.contentIdentityOrNull() ?: return render(target)
    ContentSlotHost(slots, key, entry.id, target, render)
}

/** [ContentSlotHost] 的渲染主体：按（内容身份、宿主 entry id）裁决谁渲染。 */
@Composable
internal fun ContentSlotHost(
    slots: ContentSlotRegistry,
    key: String,
    entryId: String,
    target: Any?,
    render: @Composable (Any?) -> Unit,
) {
    val slot = slots.slotFor(key)
    val owner = slot.ownerEntryId
    // 非 owner 渲染空；但 owner 已经离开返回栈时当前组合者直接接管（见 isEntryLive）。
    if (owner != null && owner != entryId && slots.isEntryLive(owner)) return
    // 认领必须在当前这次组合里完成（不能用 SideEffect 延到帧末）：同一帧里后组合的兄弟宿主必须立刻看到 owner，
    // 否则会出现两侧同时渲染同一棵组合。认领是幂等的，最多带来一次额外重组。
    if (owner != entryId) slot.ownerEntryId = entryId
    val storeOwner = remember(slot) { ContentStoreOwner(slot.viewModelStore) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
        slot.movable(target, render)
    }
}
