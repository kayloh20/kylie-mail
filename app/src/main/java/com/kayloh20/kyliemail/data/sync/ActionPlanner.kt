package com.kayloh20.kyliemail.data.sync

import com.kayloh20.kyliemail.data.db.ActionType
import com.kayloh20.kyliemail.data.mail.FolderRole

/** What the user asked for, before it is resolved against the folders that exist. */
enum class UserAction { MARK_READ, MARK_UNREAD, FLAG, UNFLAG, ARCHIVE, DELETE }

/** How a [UserAction] is carried out: the server action, where the message goes, and whether it leaves the list. */
data class ActionPlan(val type: ActionType, val targetRole: FolderRole? = null, val removesFromFolder: Boolean = false)

object ActionPlanner {
    /** Returns null when the action makes no sense here, such as archiving from the archive. */
    fun plan(action: UserAction, sourceRole: FolderRole, availableRoles: Set<FolderRole>): ActionPlan? = when (action) {
        UserAction.MARK_READ -> ActionPlan(ActionType.MARK_READ)
        UserAction.MARK_UNREAD -> ActionPlan(ActionType.MARK_UNREAD)
        UserAction.FLAG -> ActionPlan(ActionType.FLAG)
        UserAction.UNFLAG -> ActionPlan(ActionType.UNFLAG)
        UserAction.ARCHIVE -> when {
            sourceRole == FolderRole.ARCHIVE || FolderRole.ARCHIVE !in availableRoles -> null
            else -> ActionPlan(ActionType.MOVE, FolderRole.ARCHIVE, removesFromFolder = true)
        }
        UserAction.DELETE -> when {
            // Deleting from Trash, or when there is no Trash, removes the message for good.
            sourceRole == FolderRole.TRASH || FolderRole.TRASH !in availableRoles ->
                ActionPlan(ActionType.DELETE_PERMANENTLY, removesFromFolder = true)
            else -> ActionPlan(ActionType.MOVE, FolderRole.TRASH, removesFromFolder = true)
        }
    }
}
