/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.base.security;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.trino.cache.NonEvictableLoadingCache;
import io.trino.spi.TrinoException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.base.Verify.verify;
import static io.trino.cache.SafeCaches.buildNonEvictableCache;
import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;

/**
 * Matches a rule's user, role and group patterns against a principal, and substitutes the
 * capturing groups of the one matching pattern that has them ({@code $1}, {@code $2}, ...)
 * into the rule's resource patterns. Captured values are substituted as literals, and the rest of
 * the resource pattern is kept as written, backslash escapes included.
 * <p>
 * When several of the principal's roles or groups match, each is tried in turn, and a call
 * matches if a single one of them satisfies every resource pattern passed to that call.
 * Rules may take capturing groups from at most one of user, role and group; otherwise the
 * match methods throw {@code CONFIGURATION_INVALID}.
 */
public class ReplacePatternMatcher
{
    // Bounded by rules x capture values; every table filtered re-derives the same few patterns
    private static final NonEvictableLoadingCache<String, Pattern> SUBSTITUTED_PATTERNS = buildNonEvictableCache(
            CacheBuilder.newBuilder().maximumSize(10_000),
            CacheLoader.from(Pattern::compile));

    private final Optional<Pattern> userRegex;
    private final Optional<Pattern> roleRegex;
    private final Optional<Pattern> groupRegex;

    private final String user;
    private final Set<String> roles;
    private final Set<String> groups;

    public ReplacePatternMatcher(Optional<Pattern> userRegex, Optional<Pattern> roleRegex, Optional<Pattern> groupRegex, String user, Set<String> roles, Set<String> groups)
    {
        this.userRegex = userRegex;
        this.roleRegex = roleRegex;
        this.groupRegex = groupRegex;

        this.user = user;
        this.roles = roles;
        this.groups = groups;
    }

    public boolean matchCatalog(Optional<Pattern> catalogRegex, String catalog)
    {
        return match() && matchFields(ImmutableList.of(new Field("catalog", catalogRegex, catalog)));
    }

    public boolean matchSchema(Optional<Pattern> schemaRegex, String schema)
    {
        return match() && matchFields(ImmutableList.of(new Field("schema", schemaRegex, schema)));
    }

    public boolean matchCatalogAndSchema(Optional<Pattern> catalogRegex, String catalog, Optional<Pattern> schemaRegex, String schema)
    {
        return match() && matchFields(ImmutableList.of(
                new Field("catalog", catalogRegex, catalog),
                new Field("schema", schemaRegex, schema)));
    }

    public boolean matchSchemaAndTable(Optional<Pattern> schemaRegex, String schema, Optional<Pattern> tableRegex, String table)
    {
        return match() && matchFields(ImmutableList.of(
                new Field("schema", schemaRegex, schema),
                new Field("table", tableRegex, table)));
    }

    public boolean matchCatalogSchemaAndTable(Optional<Pattern> catalogRegex, String catalog, Optional<Pattern> schemaRegex, String schema, Optional<Pattern> tableRegex, String table)
    {
        return match() && matchFields(ImmutableList.of(
                new Field("catalog", catalogRegex, catalog),
                new Field("schema", schemaRegex, schema),
                new Field("table", tableRegex, table)));
    }

    public boolean matchQueryAccessRule(Optional<Pattern> queryOwnerRegex, Optional<String> queryOwner)
    {
        if (queryOwner.isEmpty() && queryOwnerRegex.isEmpty()) {
            return match();
        }

        return match() && queryOwner.isPresent() && matchFields(ImmutableList.of(new Field("query_owner", queryOwnerRegex, queryOwner.get())));
    }

    private boolean match()
    {
        return userRegex.map(regex -> regex.matcher(user).matches()).orElse(true) &&
                roleRegex.map(regex -> roles.stream().anyMatch(role -> regex.matcher(role).matches())).orElse(true) &&
                groupRegex.map(regex -> groups.stream().anyMatch(group -> regex.matcher(group).matches())).orElse(true);
    }

    private boolean matchFields(List<Field> fields)
    {
        List<CaptureSource> captureSources = captureSources();
        if (captureSources.isEmpty()) {
            return fields.stream().allMatch(field -> matchField(field, Optional.empty()));
        }
        return captureSources.stream()
                .anyMatch(source -> fields.stream().allMatch(field -> matchField(field, Optional.of(source))));
    }

    private static boolean matchField(Field field, Optional<CaptureSource> source)
    {
        if (field.regex().isEmpty()) {
            return true;
        }
        Pattern regex = field.regex().get();
        if (source.isPresent() && regex.pattern().indexOf('$') >= 0) {
            regex = source.get().substitute(regex, field.name());
        }
        return regex.matcher(field.value()).matches();
    }

    private List<CaptureSource> captureSources()
    {
        ImmutableList.Builder<CaptureSource> sources = ImmutableList.builder();
        userRegex.filter(ReplacePatternMatcher::hasCapturingGroups)
                .filter(regex -> regex.matcher(user).matches())
                .ifPresent(regex -> sources.add(new CaptureSource("user", regex, user)));
        roleRegex.filter(ReplacePatternMatcher::hasCapturingGroups)
                .ifPresent(regex -> roles.stream()
                        .filter(role -> regex.matcher(role).matches())
                        .forEach(role -> sources.add(new CaptureSource("role", regex, role))));
        groupRegex.filter(ReplacePatternMatcher::hasCapturingGroups)
                .ifPresent(regex -> groups.stream()
                        .filter(group -> regex.matcher(group).matches())
                        .forEach(group -> sources.add(new CaptureSource("group", regex, group))));
        List<CaptureSource> captureSources = sources.build();

        if (captureSources.stream().map(CaptureSource::name).distinct().count() > 1) {
            throw new TrinoException(CONFIGURATION_INVALID, "Multiple matchers that contain capturing groups are used" +
                    " to replace patterns. This may lead to unexpected results.");
        }
        return captureSources;
    }

    private static boolean hasCapturingGroups(Pattern regex)
    {
        return regex.matcher("").groupCount() > 0;
    }

    private record Field(String name, Optional<Pattern> regex, String value) {}

    private record CaptureSource(String name, Pattern regex, String value)
    {
        // Replaces Matcher.appendReplacement, which inserted captured values as live regex and stripped the template's backslash escapes.
        // Values are quoted and the template kept as written; $n parsing still follows appendReplacement so existing rules keep their meaning.
        Pattern substitute(Pattern pattern, String field)
        {
            Matcher matcher = regex.matcher(value);
            verify(matcher.matches(), "%s does not match %s", regex, value);
            String template = pattern.pattern();
            StringBuilder replaced = new StringBuilder(template.length() + value.length());
            boolean quoted = false;
            int position = 0;
            while (position < template.length()) {
                char current = template.charAt(position);
                if (current == '\\' && position + 1 < template.length() && (template.charAt(position + 1) == (quoted ? 'E' : 'Q'))) {
                    quoted = !quoted;
                    replaced.append(current).append(template.charAt(position + 1));
                    position += 2;
                }
                else if (current == '\\' && !quoted && position + 1 < template.length()) {
                    replaced.append(current).append(template.charAt(position + 1));
                    position += 2;
                }
                else if (current == '$' && position + 1 < template.length() && isDigit(template.charAt(position + 1))) {
                    int group = template.charAt(position + 1) - '0';
                    position += 2;
                    // Like Matcher.appendReplacement, the first digit always belongs to the reference and each further digit only while the group exists
                    while (position < template.length() && isDigit(template.charAt(position)) && group * 10 + (template.charAt(position) - '0') <= matcher.groupCount()) {
                        group = group * 10 + (template.charAt(position) - '0');
                        position++;
                    }
                    if (group > matcher.groupCount()) {
                        throw new TrinoException(
                                CONFIGURATION_INVALID,
                                field + " in replace pattern refers to a capturing group that does not exist in " + name);
                    }
                    String literal = Pattern.quote(nullToEmpty(matcher.group(group)));
                    // \Q..\E does not nest, so a reference inside a quoted section closes it around the value
                    replaced.append(quoted ? "\\E" + literal + "\\Q" : literal);
                }
                else {
                    replaced.append(current);
                    position++;
                }
            }
            try {
                return SUBSTITUTED_PATTERNS.getUnchecked(replaced.toString());
            }
            catch (UncheckedExecutionException e) {
                if (e.getCause() instanceof PatternSyntaxException syntaxException) {
                    throw new TrinoException(
                            CONFIGURATION_INVALID,
                            "%s in replace pattern is not a valid pattern once %s values are substituted: %s".formatted(field, name, syntaxException.getDescription()),
                            syntaxException);
                }
                throw e;
            }
        }

        private static boolean isDigit(char character)
        {
            return character >= '0' && character <= '9';
        }
    }
}
