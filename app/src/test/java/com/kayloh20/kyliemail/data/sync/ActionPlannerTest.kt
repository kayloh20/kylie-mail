package com.kayloh20.kyliemail.data.sync

import com.kayloh20.kyliemail.data.db.ActionType
import com.kayloh20.kyliemail.data.mail.FolderRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionPlannerTest {
    private val all = FolderRole.entries.toSet()

    @Test fun deleteMovesToTrash() {
        assertEquals(
            ActionPlan(ActionType.MOVE, FolderRole.TRASH, removesFromFolder = true),
            ActionPlanner.plan(UserAction.DELETE, FolderRole.INBOX, all),
        )
    }

    @Test fun deleteFromTrashIsPermanent() {
        assertEquals(
            ActionPlan(ActionType.DELETE_PERMANENTLY, removesFromFolder = true),
            ActionPlanner.plan(UserAction.DELETE, FolderRole.TRASH, all),
        )
    }

    @Test fun deleteWithoutATrashFolderIsPermanent() {
        assertEquals(
            ActionType.DELETE_PERMANENTLY,
            ActionPlanner.plan(UserAction.DELETE, FolderRole.INBOX, all - FolderRole.TRASH)?.type,
        )
    }

    @Test fun archiveMovesToArchiveUnlessThereIsNoneOrWeAreInIt() {
        assertEquals(FolderRole.ARCHIVE, ActionPlanner.plan(UserAction.ARCHIVE, FolderRole.INBOX, all)?.targetRole)
        assertNull(ActionPlanner.plan(UserAction.ARCHIVE, FolderRole.ARCHIVE, all))
        assertNull(ActionPlanner.plan(UserAction.ARCHIVE, FolderRole.INBOX, all - FolderRole.ARCHIVE))
    }

    @Test fun flagChangesStayInTheFolder() {
        val plan = ActionPlanner.plan(UserAction.MARK_READ, FolderRole.CUSTOM, all)
        assertEquals(ActionPlan(ActionType.MARK_READ), plan)
    }
}
