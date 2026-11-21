package com.darshini.crimsonjarvis

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        internal var pendingTargetPackage: String? = null
        internal var pendingSearchQuery: String? = null
        internal var pendingMessageText: String? = null
        private var stage = 0

        fun prepareAction(pkg: String, contactQuery: String, messageText: String?) {
            pendingTargetPackage = pkg
            pendingSearchQuery = contactQuery
            pendingMessageText = messageText
            stage = 0
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val targetPkg = pendingTargetPackage ?: return
        val query = pendingSearchQuery ?: return
        if (event?.packageName?.toString() != targetPkg) return

        val root = rootInActiveWindow ?: return

        if (stage == 0) {
            val searchBtns = root.findAccessibilityNodeInfosByText("Search")
            for (node in searchBtns) {
                if (clickNodeOrParent(node)) {
                    stage = 1
                    break
                }
            }
            val editNode = findFirstEditText(root)
            if (editNode != null) {
                setNodeText(editNode, query)
                stage = 2
                handler.postDelayed({ clickMatchingChatRow(query) }, 950)
            }
        } else if (stage == 1) {
            val editNode = findFirstEditText(root)
            if (editNode != null) {
                setNodeText(editNode, query)
                stage = 2
                handler.postDelayed({ clickMatchingChatRow(query) }, 950)
            }
        }
    }

    private fun clickMatchingChatRow(contactName: String) {
        val root = rootInActiveWindow ?: return
        val matches = root.findAccessibilityNodeInfosByText(contactName)
        for (node in matches) {
            if (node.className?.toString() != "android.widget.EditText") {
                if (clickNodeOrParent(node)) {
                    val msgToSend = pendingMessageText
                    pendingSearchQuery = null
                    pendingTargetPackage = null
                    stage = 0
                    if (!msgToSend.isNullOrBlank()) {
                        handler.postDelayed({ typeAndSendMessage(msgToSend) }, 850)
                    }
                    return
                }
            }
        }
        pendingSearchQuery = null
        pendingTargetPackage = null
        stage = 0
    }

    private fun typeAndSendMessage(message: String) {
        val root = rootInActiveWindow ?: return
        val msgBox = findFirstEditText(root) ?: return
        setNodeText(msgBox, message)
        handler.postDelayed({
            val updatedRoot = rootInActiveWindow ?: return@postDelayed
            val sendBtns = updatedRoot.findAccessibilityNodeInfosByText("Send")
            for (btn in sendBtns) {
                if (clickNodeOrParent(btn)) break
            }
            pendingMessageText = null
        }, 450)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var curr = node
        while (curr != null) {
            if (curr.isClickable) {
                return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            curr = curr.parent
        }
        return false
    }

    private fun findFirstEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString() == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditText(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}
}
