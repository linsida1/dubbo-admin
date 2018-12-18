/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.admin.common.util;

import org.apache.dubbo.admin.model.domain.Route;
import org.apache.dubbo.admin.model.dto.AccessDTO;
import org.apache.dubbo.admin.model.dto.ConditionRouteDTO;
import org.apache.dubbo.admin.model.store.BlackWhiteList;
import org.apache.dubbo.admin.model.store.RoutingRuleDTO;
import org.apache.dubbo.common.utils.StringUtils;

import java.text.ParseException;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Router rule can be divided into two parts, When Condition and Then Condition <br>
 * When/Then Confition is expressed in a style of (KV) pair, the V part of the condition pair can contain multiple values (a list) <br>
 * The meaning of Rule: If a request matches When Condition, then use Then Condition to filter providers (only providers match Then Condition will be returned). <br>
 * The process of using Conditions to match consumers and providers is called `Filter`.
 * When Condition are used to filter ConsumersController, while Then Condition are used to filter ProvidersController.
 * RouteRule performs like this: If a Consumer matches When Condition, then only return the ProvidersController matches Then Condition. This means RouteRule should be applied to current Consumer and the providers returned are filtered by RouteRule.<br>
 *
 * An example of ConditionRoute Rule：<code>
 * key1 = value11,value12 & key2 = value21 & key2 != value22 => key3 = value3 & key4 = value41,vlaue42 & key5 !=value51
 * </code>。
 * The part before <code>=></code> is called When Condition, it's a KV pair; the follower part is Then Condition, also a KV pair. V part in KV can have more than one value, separated by ','<br><br>
 *
 * Value object, thread safe.
 *
 */
public class RouteRule {
    @SuppressWarnings("unchecked")
    static RouteRule EMPTY = new RouteRule(Collections.EMPTY_MAP, Collections.EMPTY_MAP);
    private static Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    private static Pattern CONDITION_SEPERATOR = Pattern.compile("(.*)=>(.*)");
    private static Pattern VALUE_LIST_SEPARATOR = Pattern.compile("\\s*,\\s*");
    final Map<String, MatchPair> whenCondition;
    final Map<String, MatchPair> thenCondition;
    private volatile String tostring = null;

    // FIXME
    private RouteRule(Map<String, MatchPair> when, Map<String, MatchPair> then) {
        for (Map.Entry<String, MatchPair> entry : when.entrySet()) {
            entry.getValue().freeze();
        }
        for (Map.Entry<String, MatchPair> entry : then.entrySet()) {
            entry.getValue().freeze();
        }

        // NOTE: Both When Condition and Then Condition can be null
        this.whenCondition = when;
        this.thenCondition = then;
    }

    public static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // K-V pair, contains matches part and mismatches part
        MatchPair pair = null;
        // V part has multiple values
        Set<String> values = null;
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        while (matcher.find()) { // match one by one
            String separator = matcher.group(1);
            String content = matcher.group(2);
            // The expression starts
            if (separator == null || separator.length() == 0) {
                pair = new MatchPair();
                condition.put(content, pair);
            }
            // The KV starts
            else if ("&".equals(separator)) {
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    condition.put(content, pair);
                }

            }
            // The Value part of KV starts
            else if ("=".equals(separator)) {
                if (pair == null)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());

                values = pair.matches;
                values.add(content);
            }
            // The Value part of KV starts
            else if ("!=".equals(separator)) {
                if (pair == null)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());

                values = pair.unmatches;
                values.add(content);
            }
            // The Value part of KV has multiple values, separated by ','
            else if (",".equals(separator)) { // separated by ','
                if (values == null || values.size() == 0)
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    /**
     * Parse the RouteRule as a string into an object.
     *
     * @throws ParseException RouteRule string format is wrong. The following input conditions, RouteRule are illegal.
     * <ul> <li> input is <code>null</code>。
     * <li> input is "" or " "。
     * <li> input Rule doesn't have a When Condition
     * <li> input Rule doesn't have a Then Condition
     * </ul>
     */
    public static RouteRule parse(Route conditionRoute) throws ParseException {
        if (conditionRoute == null)
            throw new ParseException("null conditionRoute!", 0);

        if (conditionRoute.getMatchRule() == null && conditionRoute.getFilterRule() == null) {
            return parse(conditionRoute.getRule());
        }

        return parse(conditionRoute == null ? null : conditionRoute.getMatchRule(), conditionRoute == null ? null : conditionRoute.getFilterRule());
    }

    public static RouteRule parse(String whenRule, String thenRule) throws ParseException {
        /*if (whenRule == null || whenRule.trim().length() == 0) {
            throw new ParseException("Illegal route rule without when express", 0);
    	}*/
        if (thenRule == null || thenRule.trim().length() == 0) {
            throw new ParseException("Illegal route rule without then express", 0);
        }
        Map<String, MatchPair> when = parseRule(whenRule.trim());
        Map<String, MatchPair> then = parseRule(thenRule.trim());
        return new RouteRule(when, then);
    }

    public static RouteRule parse(String rule) throws ParseException {
        if (StringUtils.isBlank(rule)) {
            throw new ParseException("Illegal blank route rule", 0);
        }

        final Matcher matcher = CONDITION_SEPERATOR.matcher(rule);
        if (!matcher.matches()) throw new ParseException("condition seperator => not found!", 0);

        return parse(matcher.group(1), matcher.group(2));
    }

    /**
     * @see #parse(String)
     * @throws RuntimeException This is an wrapper exception for the {@link ParseException} thrown by the {@link #parse (String)} method.
     */
    public static RouteRule parseQuitely(Route conditionRoute) {
        try {
            return parse(conditionRoute);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public static RoutingRuleDTO insertConditionRule(RoutingRuleDTO existRule, ConditionRouteDTO conditionRoute) {
        if (existRule == null) {
            existRule = new RoutingRuleDTO();
            if (StringUtils.isNotEmpty(conditionRoute.getApplication())) {
                existRule.setKey(conditionRoute.getApplication());
                existRule.setScope(Constants.APPLICATION);
            } else {
                existRule.setKey(conditionRoute.getService());
                existRule.setScope(Constants.SERVICE);
            }
        }
        existRule.setConditions(conditionRoute.getConditions());
        existRule.setDynamic(conditionRoute.isDynamic());
        existRule.setEnabled(conditionRoute.isEnabled());
        existRule.setForce(conditionRoute.isForce());
        existRule.setRuntime(conditionRoute.isRuntime());
        existRule.setPriority(conditionRoute.getPriority());
        return existRule;
    }
    public static BlackWhiteList convertToBlackWhiteList(AccessDTO accessDTO) {
        if (accessDTO == null) {
            return null;
        }
        BlackWhiteList blackWhiteList = new BlackWhiteList();
//        if (StringUtils.isNoneEmpty(accessDTO.getApplication())) {
//            blackWhiteList.set
//        }
//        AccessDTO storeDTO = ConvertUtil.convertDTOtoStore(accessDTO);

        Set<String> whiteList = accessDTO.getWhitelist();
        Set<String> blackList = accessDTO.getBlacklist();
        List<String> conditions = new ArrayList<>();
        if (whiteList != null && whiteList.size() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("host != ");
            for (String white : whiteList) {
                sb.append(white).append(",");
            }
            sb.deleteCharAt(sb.length() - 1);
            sb.append(" =>");
            conditions.add(sb.toString());
        }
        if (blackList != null && blackList.size() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("host = ");
            for (String black : blackList) {
                sb.append(black).append(",");
            }
            sb.deleteCharAt(sb.length() - 1);
            sb.append(" =>");
            conditions.add(sb.toString());
        }
        blackWhiteList.setConditions(conditions);
        return blackWhiteList;
    }

    public static AccessDTO convertToAccessDTO(BlackWhiteList blackWhiteList, String scope, String key) {
        if (blackWhiteList == null) {
            return null;
        }
        AccessDTO accessDTO = new AccessDTO();
        if (scope.equals(Constants.APPLICATION)) {
            accessDTO.setApplication(key);
        } else {
            accessDTO.setService(key);
        }
        if (blackWhiteList.getConditions() != null) {
            for (String condition : blackWhiteList.getConditions()) {
                if (condition.contains("host != ")) {
                    //white list
                    condition = org.apache.commons.lang3.StringUtils.substringBetween(condition, "host !=", " =>").trim();
                    accessDTO.setWhitelist(new HashSet<>(Arrays.asList(condition.split(","))));
                }
                if (condition.contains("host = ")) {
                    //black list
                    condition = org.apache.commons.lang3.StringUtils.substringBetween(condition, "host =", " =>").trim();
                    accessDTO.setBlacklist(new HashSet<>(Arrays.asList(condition.split(","))));
                }
            }
        }
        return accessDTO;
    }

    public static Route convertAccessDTOtoRoute(AccessDTO accessDTO) {
        Route route = new Route();
        route.setService(accessDTO.getService());
        route.setForce(true);
        route.setFilterRule("false");
        route.setEnabled(true);

        Map<String, RouteRule.MatchPair> when = new HashMap<>();
        RouteRule.MatchPair matchPair = new RouteRule.MatchPair(new HashSet<>(), new HashSet<>());
        when.put(Route.KEY_CONSUMER_HOST, matchPair);

        if (accessDTO.getWhitelist() != null) {
            matchPair.getUnmatches().addAll(accessDTO.getWhitelist());
        }
        if (accessDTO.getBlacklist() != null) {
            matchPair.getMatches().addAll(accessDTO.getBlacklist());
        }

        StringBuilder sb = new StringBuilder();
        RouteRule.contidionToString(sb, when);
        route.setMatchRule(sb.toString());
        return route;
    }

    public static ConditionRouteDTO createConditionRouteFromRule(RoutingRuleDTO routingRuleDTO) {
        ConditionRouteDTO conditionRouteDTO = new ConditionRouteDTO();
        if (routingRuleDTO.getScope().equals(Constants.SERVICE)) {
            conditionRouteDTO.setService(routingRuleDTO.getKey());
        } else {
            conditionRouteDTO.setApplication(routingRuleDTO.getKey());
        }
        conditionRouteDTO.setConditions(routingRuleDTO.getConditions());
        conditionRouteDTO.setPriority(routingRuleDTO.getPriority());
        conditionRouteDTO.setEnabled(routingRuleDTO.isEnabled());
        conditionRouteDTO.setForce(routingRuleDTO.isForce());
        conditionRouteDTO.setRuntime(routingRuleDTO.isRuntime());
        conditionRouteDTO.setDynamic(routingRuleDTO.isDynamic());
        return conditionRouteDTO;
    }

    public static Route convertBlackWhiteListtoRoute(BlackWhiteList blackWhiteList, String scope, String key) {
        AccessDTO accessDTO = convertToAccessDTO(blackWhiteList, scope, key);
        return convertAccessDTOtoRoute(accessDTO);
    }

    static Map<String, MatchPair> parseNameAndValueListString2Condition(Map<String, String> params, Map<String, String> notParams) {
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();

        for (Entry<String, String> entry : params.entrySet()) {
            String valueListString = entry.getValue();
            if (StringUtils.isBlank(valueListString)) {
                continue;
            }
            String[] list = VALUE_LIST_SEPARATOR.split(valueListString);
            Set<String> set = new HashSet<String>();
            for (String item : list) {
                if (StringUtils.isBlank(item)) {
                    continue;
                }
                set.add(item.trim());
            }
            if (set.isEmpty()) {
                continue;
            }

            String key = entry.getKey();
            MatchPair matchPair = condition.get(key);
            if (null == matchPair) {
                matchPair = new MatchPair();
                condition.put(key, matchPair);
            }

            matchPair.matches = set;
        }
        for (Entry<String, String> entry : notParams.entrySet()) {
            String valueListString = entry.getValue();
            if (StringUtils.isBlank(valueListString)) {
                continue;
            }
            String[] list = VALUE_LIST_SEPARATOR.split(valueListString);
            Set<String> set = new HashSet<String>();
            for (String item : list) {
                if (StringUtils.isBlank(item)) {
                    continue;
                }
                set.add(item.trim());
            }
            if (set.isEmpty()) {
                continue;
            }

            String key = entry.getKey();
            MatchPair matchPair = condition.get(key);
            if (null == matchPair) {
                matchPair = new MatchPair();
                condition.put(key, matchPair);
            }

            matchPair.unmatches = set;
        }

        return condition;
    }

    public static RouteRule createFromNameAndValueListString(Map<String, String> whenParams, Map<String, String> notWhenParams,
                                                             Map<String, String> thenParams, Map<String, String> notThenParams) {
        Map<String, MatchPair> when = parseNameAndValueListString2Condition(whenParams, notWhenParams);
        Map<String, MatchPair> then = parseNameAndValueListString2Condition(thenParams, notThenParams);

        return new RouteRule(when, then);
    }

    public static RouteRule createFromCondition(Map<String, MatchPair> whenCondition, Map<String, MatchPair> thenCondition) {
        return new RouteRule(whenCondition, thenCondition);
    }

    public static RouteRule copyWithRemove(RouteRule copy, Set<String> whenParams, Set<String> thenParams) {
        Map<String, MatchPair> when = new HashMap<String, MatchPair>();
        for (Entry<String, MatchPair> entry : copy.getWhenCondition().entrySet()) {
            if (whenParams == null || !whenParams.contains(entry.getKey())) {
                when.put(entry.getKey(), entry.getValue());
            }
        }

        Map<String, MatchPair> then = new HashMap<String, MatchPair>();
        for (Entry<String, MatchPair> entry : copy.getThenCondition().entrySet()) {
            if (thenParams == null || !thenParams.contains(entry.getKey())) {
                then.put(entry.getKey(), entry.getValue());
            }
        }

        return new RouteRule(when, then);
    }

    /**
     * Replace with the new condition value.
     *
     * @param copy Replace Base
     * @param whenCondition WhenCondition to replace, if Base does not have an item, insert it directly.
     * @param thenCondition ThenCondition to replace, if Base has no items, then insert directly.
     * @return RouteRule after replacement
     */
    public static RouteRule copyWithReplace(RouteRule copy, Map<String, MatchPair> whenCondition, Map<String, MatchPair> thenCondition) {
        if (null == copy) {
            throw new NullPointerException("Argument copy is null!");
        }

        Map<String, MatchPair> when = new HashMap<String, MatchPair>();
        when.putAll(copy.getWhenCondition());
        if (whenCondition != null) {
            when.putAll(whenCondition);
        }

        Map<String, MatchPair> then = new HashMap<String, MatchPair>();
        then.putAll(copy.getThenCondition());
        if (thenCondition != null) {
            then.putAll(thenCondition);
        }

        return new RouteRule(when, then);
    }

    // TODO ToString out of the current list is out of order, should we sort?
    static void join(StringBuilder sb, Set<String> valueSet) {
        boolean isFirst = true;
        for (String s : valueSet) {
            if (isFirst) {
                isFirst = false;
            } else {
                sb.append(",");
            }

            sb.append(s);
        }
    }

    /**
     * Whether the sample passed the conditions.
     * <p>
     * If there is a Key in the KV for the sample, there is a corresponding MatchPair, and Value does not pass through MatchPair; {@code false} is returned; otherwise, {@code true} is returned.
     *
     * @see MatchPair#pass(String)
     */
    public static boolean matchCondition(Map<String, String> sample,
                                         Map<String, MatchPair> condition) {
        for (Map.Entry<String, String> entry : sample.entrySet()) {
            String key = entry.getKey();

            MatchPair pair = condition.get(key);
            if (pair != null && !pair.pass(entry.getValue())) {
                return false;
            }
        }
        return true;
    }


    // FIXME Remove such method calls
    public static String join(Set<String> valueSet) {
        StringBuilder sb = new StringBuilder(128);
        join(sb, valueSet);
        return sb.toString();
    }

    // TODO At present, the multiple Key of Condition is in disorder. Should we sort it?
    public static void contidionToString(StringBuilder sb, Map<String, MatchPair> condition) {
        boolean isFirst = true;
        for (Entry<String, MatchPair> entry : condition.entrySet()) {
            String keyName = entry.getKey();
            MatchPair p = entry.getValue();

            @SuppressWarnings("unchecked")
            Set<String>[] setArray = new Set[]{p.matches, p.unmatches};
            String[] opArray = {" = ", " != "};

            for (int i = 0; i < setArray.length; ++i) {
                if (setArray[i].isEmpty()) {
                    continue;
                }
                if (isFirst) {
                    isFirst = false;
                } else {
                    sb.append(" & ");
                }

                sb.append(keyName);
                sb.append(opArray[i]);
                join(sb, setArray[i]);
            }
        }
    }


    public boolean isWhenContainValue(String key, String value) {
        MatchPair matchPair = whenCondition.get(key);
        if (null == matchPair) {
            return false;
        }

        return matchPair.containeValue(value);
    }

    public boolean isThenContainValue(String key, String value) {
        MatchPair matchPair = thenCondition.get(key);
        if (null == matchPair) {
            return false;
        }

        return matchPair.containeValue(value);
    }

    public boolean isContainValue(String key, String value) {
        return isWhenContainValue(key, value) || isThenContainValue(key, value);
    }

    public Map<String, MatchPair> getWhenCondition() {
        return whenCondition;
    }

    public Map<String, MatchPair> getThenCondition() {
        return thenCondition;
    }

    public String getWhenConditionString() {
        StringBuilder sb = new StringBuilder(512);
        contidionToString(sb, whenCondition);
        return sb.toString();
    }

    public String getThenConditionString() {
        StringBuilder sb = new StringBuilder(512);
        contidionToString(sb, thenCondition);
        return sb.toString();
    }

    @Override
    public String toString() {
        if (tostring != null)
            return tostring;
        StringBuilder sb = new StringBuilder(512);
        contidionToString(sb, whenCondition);
        sb.append(" => ");
        contidionToString(sb, thenCondition);
        return tostring = sb.toString();
    }

    // Automatic generation with Eclipse
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((thenCondition == null) ? 0 : thenCondition.hashCode());
        result = prime * result + ((whenCondition == null) ? 0 : whenCondition.hashCode());
        return result;
    }

    // Automatic generation with Eclipse
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RouteRule other = (RouteRule) obj;
        if (thenCondition == null) {
            if (other.thenCondition != null)
                return false;
        } else if (!thenCondition.equals(other.thenCondition))
            return false;
        if (whenCondition == null) {
            if (other.whenCondition != null)
                return false;
        } else if (!whenCondition.equals(other.whenCondition))
            return false;
        return true;
    }

    public static class MatchPair {
        Set<String> matches = new HashSet<String>();
        Set<String> unmatches = new HashSet<String>();
        private volatile boolean freezed = false;

        public MatchPair() {
        }

        public MatchPair(Set<String> matches, Set<String> unmatches) {
            if (matches == null || unmatches == null) {
                throw new IllegalArgumentException("argument of MatchPair is null!");
            }

            this.matches = matches;
            this.unmatches = unmatches;
        }

        public Set<String> getMatches() {
            return matches;
        }

        public Set<String> getUnmatches() {
            return unmatches;
        }

        public MatchPair copy() {
            MatchPair ret = new MatchPair();
            ret.matches.addAll(matches);
            ret.unmatches.addAll(unmatches);
            return ret;
        }

        void freeze() {
            if (freezed) return;
            synchronized (this) {
                if (freezed) return;
                matches = Collections.unmodifiableSet(matches);
                unmatches = Collections.unmodifiableSet(unmatches);
            }
        }

        public boolean containeValue(String value) {
            return matches.contains(value) || unmatches.contains(value);
        }

        /**
         * Whether a given value is matched by the {@link MatchPair}.
         * return {@code false}, if
         * <ol>
         * <li>value is in unmatches
         * <li>matches is not null, but value is not in matches.
         * </ol>
         * otherwise, return<code>true</code>。
         */
        public boolean pass(String sample) {
            if (unmatches.contains(sample)) return false;
            if (matches.isEmpty()) return true;
            return matches.contains(sample);
        }

        @Override
        public String toString() {
            return String.format("{matches=%s,unmatches=%s}", matches.toString(), unmatches.toString());
        }

        // Automatic generation with Eclipse
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((matches == null) ? 0 : matches.hashCode());
            result = prime * result + ((unmatches == null) ? 0 : unmatches.hashCode());
            return result;
        }

        // Automatic generation with Eclipse
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            MatchPair other = (MatchPair) obj;
            if (matches == null) {
                if (other.matches != null)
                    return false;
            } else if (!matches.equals(other.matches))
                return false;
            if (unmatches == null) {
                if (other.unmatches != null)
                    return false;
            } else if (!unmatches.equals(other.unmatches))
                return false;
            return true;
        }
    }
}