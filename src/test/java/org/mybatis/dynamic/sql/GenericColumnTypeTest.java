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
import static org.mybatis.dynamic.sql.SqlBuilder.insert;
import static org.mybatis.dynamic.sql.SqlBuilder.isEqualTo;
import static org.mybatis.dynamic.sql.SqlBuilder.select;
import static org.mybatis.dynamic.sql.SqlBuilder.update;
import static org.mybatis.dynamic.sql.render.RenderingStrategies.MYBATIS3;

import java.lang.reflect.Type;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import examples.simple.PrimaryKeyColumn;
import org.apache.ibatis.type.TypeReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GenericColumnTypeTest {
    private static final Type PAYLOAD_TYPE = new TypeReference<Map<String, List<Integer>>>() {}.getRawType();
    private final SqlTable table = SqlTable.of("sample");
    private final SqlColumn<Map<String, List<Integer>>> column = table.column("payload", JDBCType.VARCHAR)
            .withJavaType(PAYLOAD_TYPE).withTypeHandler("example.JsonTypeHandler").withJavaProperty("payload");

    @Test
    void preservesMetadataAcrossImmutableCopies() {
        assertThat(List.of(column, column.as("data"), column.qualifiedWith("s"), column.descending(),
                column.asCamelCase(), column.withTypeHandler("example.OtherHandler"),
                column.withJavaProperty("otherPayload"), column.withRenderingStrategy(MYBATIS3),
                column.withParameterTypeConverter(value -> value)))
                .allSatisfy(copy -> {
                    assertThat(copy.javaGenericType()).contains(PAYLOAD_TYPE);
                    assertThat(copy.javaType()).isEmpty();
                });
        assertThat(column.alias()).isEmpty();
        assertThat(column.typeHandler()).contains("example.JsonTypeHandler");
        assertThat(column.javaProperty()).contains("payload");
    }

    @Test
    void rendersCompleteTypeInWhereBindings() {
        String sql = select(column).from(table).where(column, isEqualTo(Map.of())).build()
                .render(MYBATIS3).getSelectStatement();
        assertThat(sql).isEqualTo("select payload from sample where payload = #{parameters.p1,jdbcType=VARCHAR,"
                + "javaType=java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>,"
                + "typeHandler=example.JsonTypeHandler}");
    }

    @Test
    void rendersCompleteTypeInRecordInsertBindings() {
        String sql = insert(Map.of()).into(table).map(column).toProperty("payload").build()
                .render(MYBATIS3).getInsertStatement();
        assertThat(sql).isEqualTo("insert into sample (payload) values (#{row.payload,jdbcType=VARCHAR,"
                + "javaType=java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>,"
                + "typeHandler=example.JsonTypeHandler})");
    }

    @Test
    void rendersCompleteTypeInBothUpdateAndWhereBindings() {
        String sql = update(table).set(column).equalTo(Map.of()).where(column, isEqualTo(Map.of())).build()
                .render(MYBATIS3).getUpdateStatement();
        assertThat(sql).isEqualTo("update sample set payload = #{parameters.p1,jdbcType=VARCHAR,"
                + "javaType=java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>,"
                + "typeHandler=example.JsonTypeHandler} where payload = #{parameters.p2,jdbcType=VARCHAR,"
                + "javaType=java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>,"
                + "typeHandler=example.JsonTypeHandler}");
    }

    static Stream<Type> resolvedTypes() {
        return Stream.of(new TypeReference<List<? extends Number>>() {}.getRawType(),
                new TypeReference<List<? super Integer>>() {}.getRawType(),
                new TypeReference<List<?>>() {}.getRawType(),
                new TypeReference<List<String>[]>() {}.getRawType(),
                new TypeReference<Owner<String>.Child<Integer>>() {}.getRawType());
    }

    @ParameterizedTest
    @MethodSource("resolvedTypes")
    void preservesWildcardsGenericArraysAndOwners(Type type) {
        var typed = table.column("payload").withJavaType(type).as("data");
        assertThat(typed.javaGenericType()).contains(type);
        assertThat(MYBATIS3.getFormattedJdbcPlaceholder(typed, "parameters", "p1"))
                .isEqualTo("#{parameters.p1,javaType=" + type.getTypeName() + "}");
    }

    static Stream<Type> unboundTypes() throws Exception {
        return Stream.of(Generic.class.getDeclaredField("value").getGenericType(),
                Generic.class.getDeclaredField("roles").getGenericType(),
                Generic.class.getDeclaredField("array").getGenericType(),
                Generic.class.getDeclaredField("upperBound").getGenericType(),
                Generic.class.getDeclaredField("lowerBound").getGenericType(),
                Generic.class.getDeclaredField("owner").getGenericType());
    }

    @ParameterizedTest
    @MethodSource("unboundTypes")
    void rejectsUnboundVariablesWithColumnName(Type type) {
        assertThatThrownBy(() -> table.column("roles").withJavaType(type))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("T", "roles");
    }

    @Test
    void validatesAtBuildTimeAfterTheColumnNameHasBeenSet() throws Exception {
        Type unbound = Generic.class.getDeclaredField("roles").getGenericType();
        var builder = new SqlColumn.Builder<List<String>>().withJavaType(unbound).withName("roles").withTable(table);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("T", "roles");
    }

    @Test
    void preservesClassBasedTypeInferenceAndReplacesPreviousMetadata() {
        var stringColumn = table.column("name").withJavaType(String.class);
        assertThat(stringColumn.javaType()).contains(String.class);
        assertThat(stringColumn.javaGenericType()).contains(String.class);
        SqlColumn<Map<String, List<Integer>>> generic = stringColumn.withJavaType(PAYLOAD_TYPE);
        assertThat(generic.javaType()).isEmpty();
        assertThat(generic.javaGenericType()).contains(PAYLOAD_TYPE);
        var longColumn = generic.withJavaType(Long.class);
        assertThat(longColumn.javaType()).contains(Long.class);
        assertThat(longColumn.as("number").javaGenericType()).contains(Long.class);
    }

    @Test
    void clearsPreviousMetadataWhenBuilderTypeIsRemoved() {
        var builder = new SqlColumn.Builder<String>().withName("name").withTable(table).withJavaType(PAYLOAD_TYPE);
        var noGenericType = builder.withJavaType((Type) null).build();
        assertThat(noGenericType.javaType()).isEmpty();
        assertThat(noGenericType.javaGenericType()).isEmpty();
        var noClassType = builder.withJavaType(String.class).withJavaType((Class<String>) null).build();
        assertThat(noClassType.javaType()).isEmpty();
        assertThat(noClassType.as("data").javaGenericType()).isEmpty();
    }

    @Test
    void preservesJvmClassNamesForArrayBindings() {
        var array = table.column("payload").withJavaType(String[].class).as("data");
        assertThat(MYBATIS3.getFormattedJdbcPlaceholder(array, "parameters", "p1"))
                .isEqualTo("#{parameters.p1,javaType=[Ljava.lang.String;}");
        assertThat(MYBATIS3.getRecordBasedInsertBinding(array, "row.payload"))
                .isEqualTo("#{row.payload,javaType=[Ljava.lang.String;}");
    }

    @Test
    void delegatesReflectiveClassesToExistingBuilderOverrides() {
        var builder = new LegacyBuilder();
        builder.withName("name").withTable(table);
        builder.withJavaType((Type) String.class);
        assertThat(builder.classTypeCalls).isEqualTo(1);
        assertThat(builder.build().javaGenericType()).contains(String.class);
        builder.withJavaType((Type) null);
        assertThat(builder.classTypeCalls).isEqualTo(2);
        assertThat(builder.build().javaGenericType()).isEmpty();
    }

    @Test
    void honorsExistingJavaTypeOverrides() {
        var legacy = new SqlColumn<String>(new SqlColumn.Builder<String>()
                .withName("name").withTable(table).withJavaType(String.class)) {
            @Override
            public Optional<Class<String>> javaType() {
                return Optional.empty();
            }
        };
        assertThat(legacy.javaGenericType()).isEmpty();
        assertThat(MYBATIS3.getFormattedJdbcPlaceholder(legacy, "parameters", "p1"))
                .isEqualTo("#{parameters.p1}");
    }

    @Test
    void preservesExtendedColumnsAndTheirAdditionalAttributes() {
        var primaryKey = new PrimaryKeyColumn.Builder<String>().isPrimaryKeyColumn(true)
                .withName("payload").withTable(table).build();
        var typed = primaryKey.withJavaType(PAYLOAD_TYPE).as("data").qualifiedWith("s").descending();
        assertThat(typed).isInstanceOf(PrimaryKeyColumn.class);
        assertThat(((PrimaryKeyColumn<?>) typed).isPrimaryKeyColumn()).isTrue();
        assertThat(typed.javaGenericType()).contains(PAYLOAD_TYPE);
    }

    static class LegacyBuilder extends SqlColumn.Builder<String> {
        private int classTypeCalls;

        @Override
        public LegacyBuilder withJavaType(@Nullable Class<String> javaType) {
            classTypeCalls++;
            super.withJavaType(javaType);
            return this;
        }
    }

    static class Generic<T> {
        T value;
        List<T> roles;
        T[] array;
        List<? extends T> upperBound;
        List<? super T> lowerBound;
        Owner<T>.Child<Integer> owner;
    }

    static class Owner<T> {
        class Child<U> {
        }
    }
}
