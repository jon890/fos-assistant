package com.bifos.assistant.workspace.domain;

/** Who may run in a workspace. */
public enum WorkspaceVisibility {
    /** Only the owner. Workspaces holding health records or job applications belong here. */
    PRIVATE,
    /** Every family member. */
    FAMILY
}
