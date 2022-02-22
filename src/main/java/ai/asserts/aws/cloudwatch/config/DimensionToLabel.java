/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@EqualsAndHashCode
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DimensionToLabel {
    private String namespace;
    /**
     * The dimension name. For e.g. for DynamoDB Table, the table name is present in the dimension
     * <code>TableName</code>
     */
    private String dimensionName;

    /**
     * By default most resource names are mapped to the `job` label
     */
    private String mapToLabel = "job";
}
