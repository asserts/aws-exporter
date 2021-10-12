/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.asserts.aws.resource.ResourceType.S3Bucket;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static ai.asserts.aws.resource.ResourceType.SQSQueue;
import static ai.asserts.aws.resource.ResourceType.DynamoDBTable;

@Component
public class ResourceMapper {
    private static final Pattern SQS_QUEUE_ARN_PATTERN = Pattern.compile("arn:aws:sqs:(.*?):.*?:(.+)");
    private static final Pattern DYNAMODB_TABLE_ARN_PATTERN = Pattern.compile("arn:aws:dynamodb:(.*?):.*?:table/(.+?)(/.+)?");
    private static final Pattern LAMBDA_ARN_PATTERN = Pattern.compile("arn:aws:lambda:(.*?):.*?:function:(.+?)(:.+)?");
    private static final Pattern S3_ARN_PATTERN = Pattern.compile("arn:aws:s3:(.*?):.*?:(.+?)");

    private final List<Mapper> mappers = new ImmutableList.Builder<Mapper>()
            .add(arn -> {
                if (arn.contains("sqs")) {
                    Matcher matcher = SQS_QUEUE_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(SQSQueue)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains("dynamodb") && arn.contains("table")) {
                    Matcher matcher = DYNAMODB_TABLE_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(DynamoDBTable)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains("lambda") && arn.contains("function")) {
                    Matcher matcher = LAMBDA_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(LambdaFunction)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains("s3")) {
                    Matcher matcher = S3_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(S3Bucket)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .build();

    public Optional<Resource> map(String arn) {
        return mappers.stream()
                .map(mapper -> mapper.get(arn))
                .filter(Optional::isPresent)
                .findFirst().orElse(Optional.empty());
    }

    public interface Mapper {
        Optional<Resource> get(String arn);
    }
}

