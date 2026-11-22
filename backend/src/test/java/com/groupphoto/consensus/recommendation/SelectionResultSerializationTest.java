package com.groupphoto.consensus.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 固定 {@link SelectionResult} 的 JSON 形态与 OpenAPI {@code Selection} 契约一致：{@code metrics} 必须作为对象
 * 存在，未计算（{@code null}）的效用字段由响应映射省略，而不是输出为显式 {@code null}。
 *
 * <p>项目的 Jackson 配置未设置 {@code default-property-inclusion}，因此以 {@code @JsonInclude(NON_NULL)} 注解为准；
 * 这里用同一套 Jackson 注解规则验证序列化结果。
 */
class SelectionResultSerializationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void uncomputedUtilityFieldsAreOmittedNotEmittedAsNull() {
        SelectionResult.Metrics metrics = new SelectionResult.Metrics(0, null, null);
        SelectionResult result = new SelectionResult(List.of(), 3, false,
                List.of(new SelectionResult.ReasonCount(SelectionResult.ReasonCode.INSUFFICIENT_CANDIDATES, 3)),
                metrics);

        JsonNode node = json.valueToTree(result);

        assertThat(node.path("metrics").isObject()).isTrue();
        assertThat(node.path("metrics").has("minimumMemberUtility")).isFalse();
        assertThat(node.path("metrics").has("totalMemberUtility")).isFalse();
        assertThat(node.path("metrics").path("eligiblePhotoCount").asInt()).isZero();
    }

    @Test
    void metricsObjectMustBePresent() {
        assertThatThrownBy(() -> new SelectionResult(List.of(), 3, false, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
