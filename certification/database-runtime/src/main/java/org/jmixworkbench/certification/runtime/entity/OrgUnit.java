package org.jmixworkbench.certification.runtime.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@JmixEntity
@Table(name = "JVW_ORG_UNIT")
@Entity(name = "jvwcert_OrgUnit")
public class OrgUnit {

    @Id
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "CODE", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "NAME", nullable = false, length = 255)
    private String name;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
