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

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 内容槽的搬运语义：纯 Compose runtime 组合（不需要 UI，也不需要模拟器）。
 *
 * 测的是生产实现 [ContentSlotHost]：两个宿主同时存在、只有一个拥有内容时，切换所有权必须把**同一棵组合**搬过去，
 * `remember` 的值随之移动而不是重新初始化——这正是 `rememberSaveable` 跨宿主存取做不到的事，也是横竖切换不丢
 * 页面状态的关键。用例同时守住了搬运的形状：把 `ContentSlot.movable` 改回“参数是内容 lambda”的写法，这里会失败。
 */
class ContentSlotHostTest {
    private class Probe {
        /** 每次内容被组合时记录 “宿主:值”，用于确认哪一侧真正渲染了。 */
        val rendered = mutableListOf<String>()
        val stores = mutableListOf<ViewModelStore?>()

        /** 没有任何宿主渲染时返回哨兵值，便于直接看出失败原因。 */
        fun last(): String = rendered.lastOrNull() ?: "<未渲染>"
    }

    /**
     * 页面本体：把 seed 作为 `remember` 的初值。
     *
     * seed 之后会改成 99，所以“读到 7”只可能是状态被搬了过来；读到 99 则是重新初始化。
     */
    @Composable
    private fun PageBody(host: String, seed: () -> Int, probe: Probe) {
        var value by remember { mutableIntStateOf(seed()) }
        probe.rendered += "$host:$value"
        probe.stores += LocalViewModelStoreOwner.current?.viewModelStore
    }

    @Test
    fun ownership_switch_moves_the_composition_and_keeps_its_state() = slotComposition { scope ->
        val slots = ContentSlotRegistry(ContentViewModelStores())
        val probe = Probe()
        val seed = mutableStateOf(7)
        // 内容定义唯一一处（与生产接线一致）：宿主差异只走 target。
        val render: @Composable (Any?) -> Unit = { host -> PageBody(host as String, { seed.value }, probe) }
        slots.slotFor(KEY).ownerEntryId = HOST_A

        scope.setContent {
            ContentSlotHost(slots, KEY, HOST_A, HOST_A, render)
            ContentSlotHost(slots, KEY, HOST_B, HOST_B, render)
        }

        assertEquals("$HOST_A:7", probe.last())

        probe.rendered.clear()
        seed.value = 99
        scope.idle()

        // 同一个宿主的重组不会重新初始化状态。
        assertEquals("$HOST_A:7", probe.last())

        probe.rendered.clear()
        slots.slotFor(KEY).ownerEntryId = HOST_B
        scope.idle()

        // 所有权切到另一个宿主：只有新宿主渲染，而且值仍是 7（不是新 seed 99）——状态随组合搬过去了。
        assertEquals("$HOST_B:7", probe.last())
        assertTrue(probe.rendered.none { it.startsWith(HOST_A) })
    }

    @Test
    fun owner_that_left_the_back_stack_is_reclaimed_by_the_composing_host() = slotComposition { scope ->
        // owner 已经被 pop（不在返回栈里）：正在组合的宿主当帧接管，而不是渲染空白等下一帧。
        val slots = ContentSlotRegistry(ContentViewModelStores(), isEntryLive = { it != HOST_A })
        val probe = Probe()
        val seed = mutableStateOf(7)
        val render: @Composable (Any?) -> Unit = { host -> PageBody(host as String, { seed.value }, probe) }
        slots.slotFor(KEY).ownerEntryId = HOST_A

        scope.setContent {
            ContentSlotHost(slots, KEY, HOST_B, HOST_B, render)
        }

        assertEquals("$HOST_B:7", probe.last())
        assertEquals(HOST_B, slots.slotFor(KEY).ownerEntryId)
    }

    @Test
    fun first_composer_claims_a_slot_without_owner() = slotComposition { scope ->
        val slots = ContentSlotRegistry(ContentViewModelStores())
        val probe = Probe()
        val render: @Composable (Any?) -> Unit = { host -> PageBody(host as String, { 7 }, probe) }

        scope.setContent {
            ContentSlotHost(slots, KEY, HOST_A, HOST_A, render)
            ContentSlotHost(slots, KEY, HOST_B, HOST_B, render)
        }

        assertEquals(HOST_A, slots.slotFor(KEY).ownerEntryId)
        scope.idle()
        // 认领之后只有 owner 继续渲染。
        assertEquals("$HOST_A:7", probe.last())
    }

    @Test
    fun page_resolves_view_model_store_from_the_slot() = slotComposition { scope ->
        val stores = ContentViewModelStores()
        val slots = ContentSlotRegistry(stores)
        val probe = Probe()
        val render: @Composable (Any?) -> Unit = { host -> PageBody(host as String, { 7 }, probe) }
        slots.slotFor(KEY).ownerEntryId = HOST_A

        scope.setContent {
            ContentSlotHost(slots, KEY, HOST_A, HOST_A, render)
        }

        // 槽内页面拿到的是内容级 store，而不是宿主页面的 store：旧 entry 销毁不会 clear 掉正在使用的 ViewModel。
        assertSame(stores.storeFor(KEY), probe.stores.last())
        assertTrue(probe.stores.last() !== scope.hostStore)
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit
    }

    private interface SlotCompositionScope {
        /** 宿主页面的 ViewModelStore：槽内页面必须拿到内容级 store，而不是它。 */
        val hostStore: ViewModelStore

        suspend fun setContent(content: @Composable () -> Unit)

        /** 推进重组与副作用，直到组合稳定。 */
        suspend fun idle()
    }

    private fun slotComposition(body: suspend (SlotCompositionScope) -> Unit) {
        runBlocking {
            val clock = BroadcastFrameClock()
            // Unconfined：帧回调与副作用都跑在测试线程上，断言不需要额外同步。
            val frameContext = Dispatchers.Unconfined + clock
            val recomposer = Recomposer(frameContext)
            val composition = Composition(UnitApplier(), recomposer)
            // 独立的 scope：Recomposer 可能一直挂在帧上，不能让 runBlocking 等它退出。
            val recomposerScope = CoroutineScope(frameContext)
            recomposerScope.launch { recomposer.runRecomposeAndApplyChanges() }
            val hostStore = ViewModelStore()
            val hostOwner = object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = hostStore
            }
            val scope = object : SlotCompositionScope {
                override val hostStore: ViewModelStore = hostStore

                override suspend fun setContent(content: @Composable () -> Unit) {
                    composition.setContent {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides hostOwner) {
                            content()
                        }
                    }
                    idle()
                }

                override suspend fun idle() {
                    repeat(IDLE_STEPS) {
                        // 裸组合里没有 UI 事件循环派发快照通知：不通知就不会有失效，也就不会有下一个重组帧。
                        Snapshot.sendApplyNotifications()
                        if (clock.hasAwaiters) clock.sendFrame(0L)
                        yield()
                    }
                }
            }
            try {
                withTimeout(COMPOSITION_TIMEOUT_MILLIS) { body(scope) }
            } finally {
                recomposer.cancel()
                composition.dispose()
                recomposerScope.cancel()
            }
        }
    }

    private companion object {
        const val KEY = "article/Answer/318"
        const val HOST_A = "entry-a"
        const val HOST_B = "entry-b"
        const val IDLE_STEPS = 6
        const val COMPOSITION_TIMEOUT_MILLIS = 60_000L
    }
}
