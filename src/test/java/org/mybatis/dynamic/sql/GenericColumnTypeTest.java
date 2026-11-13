/*
 *    Copyright 2016-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.dynamic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mybatis.dynamic.sql.SqlBuilder.*;
import static org.mybatis.dynamic.sql.render.RenderingStrategies.MYBATIS3;

import java.lang.reflect.Type;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.type.TypeReference;
import org.junit.jupiter.api.Test;

class GenericColumnTypeTest {
    @Test
    void carriesFullTypeThroughRenderingAndImmutableCopies() {
        Type type = new TypeReference<Map<String, List<Integer>>>() {}.getRawType();
        SqlTable table = SqlTable.of("sample");
        SqlColumn<Map<String, List<Integer>>> column = table.column("payload", JDBCType.VARCHAR)
                .withJavaType(type).withTypeHandler("example.JsonTypeHandler").withJavaProperty("payload");
        assertThat(column.javaGenericType()).contains(type);
        assertThat(column.as("data").qualifiedWith("s").descending().javaGenericType()).contains(type);
        assertThat(select(column).from(table).where(column, isEqualTo(Map.of())).build().render(MYBATIS3)
                .getSelectStatement()).contains("javaType=" + type.getTypeName(), "typeHandler=example.JsonTypeHandler");
        assertThat(insert(Map.of()).into(table).map(column).toProperty("payload").build().render(MYBATIS3)
                .getInsertStatement()).contains("javaType=" + type.getTypeName());
        assertThat(update(table).set(column).equalTo(Map.of()).where(column, isEqualTo(Map.of()))
                .build().render(MYBATIS3).getUpdateStatement()).contains("javaType=" + type.getTypeName());
    }

    @Test
    void preservesWildcardBoundsAndRejectsUnresolvedVariables() throws Exception {
        Type wildcard = new TypeReference<List<? extends Number>>() {}.getRawType();
        var table = SqlTable.of("sample");
        assertThat(table.column("roles").withJavaType(wildcard).javaGenericType()).contains(wildcard);
        Type unbound = Generic.class.getDeclaredField("roles").getGenericType();
        assertThatThrownBy(() -> table.column("roles").withJavaType(unbound))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("T", "roles");
    }

    static class Generic<T> {
        List<T> roles;
    }

    @Test
    void preservesClassBasedApiAndClearsOldMetadataWhenReplacingType() {
        SqlColumn<String> column = SqlTable.of("sample").column("name").withJavaType(String.class);
        assertThat(column.javaType()).contains(String.class);
        assertThat(column.javaGenericType()).contains(String.class);
        SqlColumn<List<Integer>> generic = column.withJavaType(new TypeReference<List<Integer>>() {}.getRawType());
        assertThat(generic.javaType()).isEmpty();
        assertThat(generic.withJavaType(Long.class).javaGenericType()).contains(Long.class);
    }
}
