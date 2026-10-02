package com.bifos.assistant.people.application.model;

import com.bifos.assistant.people.domain.AllowedPerson;

/** 허용 목록의 사람 하나와, 그 사람이 들어온 적이 있는지({@code app_user} 가 있는지)다. */
public record PersonAccess(AllowedPerson person, boolean joined) {}
