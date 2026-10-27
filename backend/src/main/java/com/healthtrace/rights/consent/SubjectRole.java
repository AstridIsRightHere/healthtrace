package com.healthtrace.rights.consent;

import com.healthtrace.profile.domain.Relationship;

/** SELF(本人) / PROXY(代管：代管声明不等于患者本人同意)。 */
public enum SubjectRole {
    SELF,
    PROXY;

    public static SubjectRole of(Relationship relationship) {
        return relationship == Relationship.SELF ? SELF : PROXY;
    }
}
