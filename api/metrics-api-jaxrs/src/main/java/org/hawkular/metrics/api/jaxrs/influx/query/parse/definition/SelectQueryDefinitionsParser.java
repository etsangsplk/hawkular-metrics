/*
 * Copyright 2014-2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.metrics.api.jaxrs.influx.query.parse.definition;

import static java.util.stream.Collectors.joining;

import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.AbsoluteMomentOperandContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.AggregatedColumnDefinitionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.AliasContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.AndExpressionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.ColumnDefinitionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.ColumnDefinitionListContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.DateOperandContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.DoubleFunctionArgumentContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.DoubleOperandContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.EqExpressionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.FromClauseContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.FunctionArgumentListContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.FunctionCallContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.FutureMomentOperandContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.GroupByClauseContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.GtExpressionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.IdNameContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.LimitClauseContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.LongFunctionArgumentContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.LongOperandContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.LtExpressionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.NameFunctionArgumentContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.NameOperandContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.NeqExpressionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.OrExpressionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.OrderAscContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.PastMomentOperandContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.PrefixContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.PresentMomentOperandContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.RawColumnDefinitionContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.StringFunctionArgumentContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.StringNameContext;
import static org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryParser.WhereClauseContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.hawkular.metrics.api.jaxrs.influx.InfluxTimeUnit;
import org.hawkular.metrics.api.jaxrs.influx.query.parse.InfluxQueryBaseListener;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;

/**
 * @author Thomas Segismont
 */
public class SelectQueryDefinitionsParser extends InfluxQueryBaseListener {
    private static final DateTimeFormatter DATE_FORMATTER;
    static {
        DateTimeParser millisParser = new DateTimeFormatterBuilder() //
            .appendLiteral('.') //
            .append(DateTimeFormat.forPattern("SSS")) //
            .toParser();
        DateTimeParser timeParser = new DateTimeFormatterBuilder() //
            .appendLiteral(' ') //
            .append(DateTimeFormat.forPattern("HH")) //
            .appendLiteral(':') //
            .append(DateTimeFormat.forPattern("mm")) //
            .appendLiteral(':') //
            .append(DateTimeFormat.forPattern("ss")) //
            .appendOptional(millisParser) //
            .toParser();
        DATE_FORMATTER = new DateTimeFormatterBuilder() //
            .append(DateTimeFormat.forPattern("yyyy")) //
            .appendLiteral('-') //
            .append(DateTimeFormat.forPattern("MM")) //
            .appendLiteral('-') //
            .append(DateTimeFormat.forPattern("dd")) //
            .appendOptional(timeParser) //
            .toFormatter() //
            .withZoneUTC();
    }

    private static final Pattern TIMESPAN_MATCHER;

    static {
        String regex = Arrays.stream(InfluxTimeUnit.values())
                .map(InfluxTimeUnit::getId)
                .collect(joining("|", "(\\d+)(", ")"));
        TIMESPAN_MATCHER = Pattern.compile(regex);
    }

    private SelectQueryDefinitionsBuilder definitionsBuilder = new SelectQueryDefinitionsBuilder();
    private String prefix;
    private String name;
    private String alias;
    private String function;
    private List<ColumnDefinition> columnDefinitions;
    private RawColumnDefinitionBuilder rawColumnDefinitionBuilder;
    private AggregatedColumnDefinitionBuilder aggregatedColumnDefinitionBuilder;
    private List<FunctionArgument> functionArguments;
    private Deque<BooleanExpression> booleanExpressionQueue;
    private Deque<Operand> operandQueue;

    @Override
    public void enterColumnDefinitionList(@NotNull ColumnDefinitionListContext ctx) {
        columnDefinitions = new ArrayList<>((ctx.getChildCount() + 1) / 2);
    }

    @Override
    public void exitColumnDefinitionList(@NotNull ColumnDefinitionListContext ctx) {
        definitionsBuilder.setColumnDefinitions(columnDefinitions);
    }

    @Override
    public void enterColumnDefinition(@NotNull ColumnDefinitionContext ctx) {
        alias = null;
        removeColumnDefinitionBuilder();
    }

    @Override
    public void exitColumnDefinition(@NotNull ColumnDefinitionContext ctx) {
        ColumnDefinitionBuilder columnDefinitionBuilder = getColumnDefinitionBuilder();
        columnDefinitionBuilder.setAlias(alias);
        columnDefinitions.add(columnDefinitionBuilder.createColumnDefinition());
        alias = null;
        removeColumnDefinitionBuilder();
    }

    @Override
    public void enterRawColumnDefinition(@NotNull RawColumnDefinitionContext ctx) {
        rawColumnDefinitionBuilder = new RawColumnDefinitionBuilder();
        prefix = null;
        name = null;
    }

    @Override
    public void exitRawColumnDefinition(@NotNull RawColumnDefinitionContext ctx) {
        rawColumnDefinitionBuilder.setPrefix(prefix);
        rawColumnDefinitionBuilder.setName(name);
        prefix = null;
        name = null;
    }

    @Override
    public void enterAggregatedColumnDefinition(@NotNull AggregatedColumnDefinitionContext ctx) {
        aggregatedColumnDefinitionBuilder = new AggregatedColumnDefinitionBuilder();
        function = null;
        functionArguments = null;
    }

    @Override
    public void exitAggregatedColumnDefinition(@NotNull AggregatedColumnDefinitionContext ctx) {
        aggregatedColumnDefinitionBuilder.setAggregationFunction(function);
        aggregatedColumnDefinitionBuilder.setAggregationFunctionArguments(functionArguments);
        function = null;
        functionArguments = null;
    }

    @Override
    public void enterFromClause(@NotNull FromClauseContext ctx) {
        name = null;
        alias = null;
    }

    @Override
    public void exitFromClause(@NotNull FromClauseContext ctx) {
        definitionsBuilder.setFromClause(new FromClause(name, alias));
        name = null;
        alias = null;
    }

    @Override
    public void exitGroupByClause(@NotNull GroupByClauseContext ctx) {
        String bucketType = ctx.ID().getText();
        String timespan = ctx.TIMESPAN().getText();
        Matcher matcher = TIMESPAN_MATCHER.matcher(timespan);
        if (!matcher.matches()) {
            throw new IllegalStateException("Unknown timespan format: " + timespan);
        }
        long bucketSize = Long.parseLong(matcher.group(1));
        String unitId = matcher.group(2);
        InfluxTimeUnit bucketSizeUnit = InfluxTimeUnit.findById(unitId);
        if (bucketSizeUnit == null) {
            throw new RuntimeException("Unknown time unit: " + unitId);
        }
        definitionsBuilder.setGroupByClause(new GroupByClause(bucketType, bucketSize, bucketSizeUnit));
    }

    @Override
    public void enterWhereClause(@NotNull WhereClauseContext ctx) {
        booleanExpressionQueue = new ArrayDeque<>(10);
        operandQueue = new ArrayDeque<>(10);
    }

    @Override
    public void exitWhereClause(@NotNull WhereClauseContext ctx) {
        definitionsBuilder.setWhereClause(booleanExpressionQueue.removeLast());
    }

    @Override
    public void exitOrderAsc(@NotNull OrderAscContext ctx) {
        definitionsBuilder.setOrderDesc(false);
    }

    @Override
    public void exitEqExpression(@NotNull EqExpressionContext ctx) {
        Operand rightOperand = operandQueue.removeLast();
        Operand leftOperand = operandQueue.removeLast();
        booleanExpressionQueue.addLast(new EqBooleanExpression(leftOperand, rightOperand));
    }

    @Override
    public void exitGtExpression(@NotNull GtExpressionContext ctx) {
        Operand rightOperand = operandQueue.removeLast();
        Operand leftOperand = operandQueue.removeLast();
        booleanExpressionQueue.addLast(new GtBooleanExpression(leftOperand, rightOperand));
    }

    @Override
    public void exitLtExpression(@NotNull LtExpressionContext ctx) {
        Operand rightOperand = operandQueue.removeLast();
        Operand leftOperand = operandQueue.removeLast();
        booleanExpressionQueue.addLast(new LtBooleanExpression(leftOperand, rightOperand));
    }

    @Override
    public void exitNeqExpression(@NotNull NeqExpressionContext ctx) {
        Operand rightOperand = operandQueue.removeLast();
        Operand leftOperand = operandQueue.removeLast();
        booleanExpressionQueue.addLast(new NeqBooleanExpression(leftOperand, rightOperand));
    }

    @Override
    public void exitAndExpression(@NotNull AndExpressionContext ctx) {
        BooleanExpression rightExpression = booleanExpressionQueue.removeLast();
        BooleanExpression leftExpression = booleanExpressionQueue.removeLast();
        booleanExpressionQueue.addLast(new AndBooleanExpression(leftExpression, rightExpression));
    }

    @Override
    public void exitOrExpression(@NotNull OrExpressionContext ctx) {
        BooleanExpression rightExpression = booleanExpressionQueue.removeLast();
        BooleanExpression leftExpression = booleanExpressionQueue.removeLast();
        booleanExpressionQueue.addLast(new OrBooleanExpression(leftExpression, rightExpression));
    }

    @Override
    public void enterNameOperand(@NotNull NameOperandContext ctx) {
        prefix = null;
        name = null;
    }

    @Override
    public void exitNameOperand(@NotNull NameOperandContext ctx) {
        operandQueue.addLast(new NameOperand(prefix, name));
        prefix = null;
        name = null;
    }

    @Override
    public void exitAbsoluteMomentOperand(AbsoluteMomentOperandContext ctx) {
        String timespan = ctx.TIMESPAN().getText();
        Matcher matcher = TIMESPAN_MATCHER.matcher(timespan);
        if (!matcher.matches()) {
            throw new IllegalStateException("Unknown timespan format: " + timespan);
        }
        long amount = Long.parseLong(matcher.group(1));
        String unitId = matcher.group(2);
        InfluxTimeUnit unit = InfluxTimeUnit.findById(unitId);
        if (unit == null) {
            throw new RuntimeException("Unknown time unit: " + unitId);
        }
        operandQueue.addLast(new DateOperand(new Instant(unit.convertTo(TimeUnit.MILLISECONDS, amount))));
    }

    @Override
    public void exitPastMomentOperand(@NotNull PastMomentOperandContext ctx) {
        String functionName = ctx.ID().getText();
        TerminalNode intNode = ctx.INT();
        long timeshift;
        InfluxTimeUnit timeshiftUnit;
        if (intNode == null) {
            String timespan = ctx.TIMESPAN().getText();
            Matcher matcher = TIMESPAN_MATCHER.matcher(timespan);
            if (!matcher.matches()) {
                throw new IllegalStateException("Unknown timespan format: " + timespan);
            }
            timeshift = Long.parseLong(matcher.group(1));
            String unitId = matcher.group(2);
            timeshiftUnit = InfluxTimeUnit.findById(unitId);
            if (timeshiftUnit == null) {
                throw new RuntimeException("Unknown time unit: " + unitId);
            }
        } else {
            timeshift = Long.parseLong(intNode.getText());
            timeshiftUnit = InfluxTimeUnit.MICROSECONDS;
        }
        operandQueue.addLast(new MomentOperand(functionName, -1 * timeshift, timeshiftUnit));
    }

    @Override
    public void exitFutureMomentOperand(@NotNull FutureMomentOperandContext ctx) {
        String functionName = ctx.ID().getText();
        TerminalNode intNode = ctx.INT();
        long timeshift;
        InfluxTimeUnit timeshiftUnit;
        if (intNode == null) {
            String timespan = ctx.TIMESPAN().getText();
            Matcher matcher = TIMESPAN_MATCHER.matcher(timespan);
            if (!matcher.matches()) {
                throw new IllegalStateException("Unknown timespan format: " + timespan);
            }
            timeshift = Long.parseLong(matcher.group(1));
            String unitId = matcher.group(2);
            timeshiftUnit = InfluxTimeUnit.findById(unitId);
            if (timeshiftUnit == null) {
                throw new RuntimeException("Unknown time unit: " + unitId);
            }
        } else {
            timeshift = Long.parseLong(intNode.getText());
            timeshiftUnit = InfluxTimeUnit.MICROSECONDS;
        }
        operandQueue.addLast(new MomentOperand(functionName, timeshift, timeshiftUnit));
    }

    @Override
    public void exitPresentMomentOperand(@NotNull PresentMomentOperandContext ctx) {
        String functionName = ctx.ID().getText();
        operandQueue.addLast(new MomentOperand(functionName, 0, InfluxTimeUnit.SECONDS));
    }

    @Override
    public void exitDateOperand(@NotNull DateOperandContext ctx) {
        String dateString = ctx.DATE_STRING().getText();
        dateString = dateString.substring(1, dateString.length() - 1);
        operandQueue.addLast(new DateOperand(Instant.parse(dateString, DATE_FORMATTER)));
    }

    @Override
    public void exitLongOperand(@NotNull LongOperandContext ctx) {
        long value = Long.parseLong(ctx.INT().getText());
        if (ctx.DASH() != null) {
            value = -1 * value;
        }
        operandQueue.addLast(new LongOperand(value));
    }

    @Override
    public void exitDoubleOperand(@NotNull DoubleOperandContext ctx) {
        double value = Double.parseDouble(ctx.FLOAT().getText());
        if (ctx.DASH() != null) {
            value = -1 * value;
        }
        operandQueue.addLast(new DoubleOperand(value));
    }

    @Override
    public void exitLimitClause(@NotNull LimitClauseContext ctx) {
        int limit = Integer.parseInt(ctx.INT().getText());
        definitionsBuilder.setLimitClause(new LimitClause(limit));
    }

    @Override
    public void exitPrefix(@NotNull PrefixContext ctx) {
        prefix = ctx.ID().getText();
    }

    @Override
    public void exitIdName(@NotNull IdNameContext ctx) {
        name = ctx.ID().getText();
    }

    @Override
    public void exitStringName(@NotNull StringNameContext ctx) {
        String doubleQuotedString = ctx.DOUBLE_QUOTED_STRING().getText();
        name = doubleQuotedString.substring(1, doubleQuotedString.length() - 1);
    }

    @Override
    public void exitAlias(@NotNull AliasContext ctx) {
        alias = ctx.ID().getText();
    }

    @Override
    public void exitFunctionCall(@NotNull FunctionCallContext ctx) {
        function = ctx.ID().getText();
    }

    @Override
    public void enterFunctionArgumentList(@NotNull FunctionArgumentListContext ctx) {
        functionArguments = new ArrayList<>((ctx.getChildCount() + 1) / 2);
    }

    @Override
    public void enterNameFunctionArgument(@NotNull NameFunctionArgumentContext ctx) {
        prefix = null;
        name = null;
    }

    @Override
    public void exitStringFunctionArgument(@NotNull StringFunctionArgumentContext ctx) {
        String singleQuotedString = ctx.SINGLE_QUOTED_STRING().getText();
        String value = singleQuotedString.substring(1, singleQuotedString.length() - 1);
        functionArguments.add(new StringFunctionArgument(value));
    }

    @Override
    public void exitNameFunctionArgument(@NotNull NameFunctionArgumentContext ctx) {
        NameFunctionArgument functionArgument = new NameFunctionArgument(prefix, name);
        functionArguments.add(functionArgument);
        prefix = null;
        name = null;
    }

    @Override
    public void exitDoubleFunctionArgument(@NotNull DoubleFunctionArgumentContext ctx) {
        double value = Double.parseDouble(ctx.FLOAT().getText());
        if (ctx.DASH() != null) {
            value = -1 * value;
        }
        functionArguments.add(new DoubleFunctionArgument(value));
    }

    @Override
    public void exitLongFunctionArgument(@NotNull LongFunctionArgumentContext ctx) {
        long value = Long.parseLong(ctx.INT().getText());
        if (ctx.DASH() != null) {
            value = -1 * value;
        }
        functionArguments.add(new LongFunctionArgument(value));
    }

    private ColumnDefinitionBuilder getColumnDefinitionBuilder() {
        return rawColumnDefinitionBuilder != null ? rawColumnDefinitionBuilder : aggregatedColumnDefinitionBuilder;
    }

    private void removeColumnDefinitionBuilder() {
        rawColumnDefinitionBuilder = null;
        aggregatedColumnDefinitionBuilder = null;
    }

    public SelectQueryDefinitions getSelectQueryDefinitions() {
        return definitionsBuilder.createSelectQueryDefinitions();
    }
}
