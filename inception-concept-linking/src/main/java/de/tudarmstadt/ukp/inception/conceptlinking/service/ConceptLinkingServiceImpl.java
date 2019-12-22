/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.conceptlinking.service;

import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.uima.cas.CAS;
import org.eclipse.rdf4j.common.net.ParsedIRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import de.tudarmstadt.ukp.clarin.webanno.api.RepositoryProperties;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.inception.conceptlinking.config.EntityLinkingProperties;
import de.tudarmstadt.ukp.inception.conceptlinking.feature.EntityRankingFeatureGenerator;
import de.tudarmstadt.ukp.inception.conceptlinking.ranking.BaselineRanker;
import de.tudarmstadt.ukp.inception.conceptlinking.ranking.Ranker;
import de.tudarmstadt.ukp.inception.conceptlinking.ranking.letor.ExternalLetorRanker;
import de.tudarmstadt.ukp.inception.conceptlinking.util.FileUtils;
import de.tudarmstadt.ukp.inception.kb.ConceptFeatureValueType;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.RepositoryType;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilder;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryPrimaryConditions;

@Component
public class ConceptLinkingServiceImpl
    implements InitializingBean, ConceptLinkingService
{
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final KnowledgeBaseService kbService;
    private final EntityLinkingProperties properties;
    private final RepositoryProperties repoProperties;

    private Set<String> stopwords;

    private final List<EntityRankingFeatureGenerator> featureGeneratorsProxy;
    private List<EntityRankingFeatureGenerator> featureGenerators;

    private Ranker ranker;

    @Autowired
    public ConceptLinkingServiceImpl(KnowledgeBaseService aKbService,
            EntityLinkingProperties aProperties,
            RepositoryProperties aRepoProperties,
            @Lazy @Autowired(required = false) List<EntityRankingFeatureGenerator> 
                    aFeatureGenerators)
    {
        Validate.notNull(aKbService);
        Validate.notNull(aProperties);
        
        kbService = aKbService;
        properties = aProperties;
        featureGeneratorsProxy = aFeatureGenerators;
        repoProperties = aRepoProperties;
    }
    
    @Override
    public void afterPropertiesSet() throws Exception
    {
        File stopwordsFile = new File(repoProperties.getPath(), "resources/stopwords-en.txt");
        stopwords = FileUtils.loadStopwordFile(stopwordsFile);

        ranker = new BaselineRanker(featureGeneratorsProxy, stopwords,
                properties.getCandidateDisplayLimit(), properties.getMentionContextSize());
        ranker = new ExternalLetorRanker();
    }
    
    @EventListener
    public void onContextRefreshedEvent(ContextRefreshedEvent aEvent)
    {
        init();
    }

    /* package private */ void init()
    {
        List<EntityRankingFeatureGenerator> generators = new ArrayList<>();

        if (featureGeneratorsProxy != null) {
            generators.addAll(featureGeneratorsProxy);
            AnnotationAwareOrderComparator.sort(generators);
        
            for (EntityRankingFeatureGenerator generator : generators) {
                log.info("Found entity ranking feature generator: {}",
                        ClassUtils.getAbbreviatedName(generator.getClass(), 20));
            }
        }

        featureGenerators = unmodifiableList(generators);
    }

    private SPARQLQueryPrimaryConditions newQueryBuilder(ConceptFeatureValueType aValueType,
            KnowledgeBase aKB)
    {
        switch (aValueType) {
        case ANY_OBJECT:
            return SPARQLQueryBuilder.forItems(aKB);
        case CONCEPT:
            return SPARQLQueryBuilder.forClasses(aKB);
        case INSTANCE:
            return SPARQLQueryBuilder.forInstances(aKB);
        case PROPERTY:
            return SPARQLQueryBuilder.forProperties(aKB);
        default:
            throw new IllegalArgumentException("Unknown item type: [" + aValueType + "]");
        }
    }
    
    public Set<KBHandle> generateCandidates(KnowledgeBase aKB, String aConceptScope,
            ConceptFeatureValueType aValueType, String aQuery, String aMention)
    {
        // If the query of the user is smaller or equal to this threshold, then we only use it for
        // exact matching. If it is longer, we look for concepts which start with or which contain
        // the users input. This is meant as a performance optimization for large KBs where we 
        // want to avoid long reaction times when there is large number of candidates (which is
        // very likely when e.g. searching for all items starting with or containing a specific
        // letter.
        final int threshold = RepositoryType.LOCAL.equals(aKB.getType()) ? 0 : 3;
        
        long startTime = currentTimeMillis();
        Set<KBHandle> result = new LinkedHashSet<>();
        
        if (aQuery != null) {
            ParsedIRI iri = null;
            try {
                iri = new ParsedIRI(aQuery);
            }
            catch (URISyntaxException | NullPointerException e) {
                // Skip match by IRI.
            }
            if (iri != null && iri.isAbsolute()) {
                SPARQLQueryPrimaryConditions iriMatchBuilder = newQueryBuilder(aValueType, aKB)
                        .withIdentifier(aQuery);

                if (aConceptScope != null) {
                    iriMatchBuilder.descendantsOf(aConceptScope);
                }
                
                iriMatchBuilder
                        .retrieveLabel()
                        .retrieveDescription();
                        
                List<KBHandle> iriMatches;
                if (aKB.isReadOnly()) {
                    iriMatches = kbService.listHandlesCaching(aKB, iriMatchBuilder, true);
                }
                else {
                    iriMatches = kbService.read(aKB, conn -> iriMatchBuilder.asHandles(conn, true));
                }
                
                log.debug("Found [{}] candidates exactly matching IRI [{}]", iriMatches.size(),
                        aQuery);

                result.addAll(iriMatches);
            }
        }
        
        SPARQLQueryPrimaryConditions exactBuilder = newQueryBuilder(aValueType, aKB);
        
        if (aConceptScope != null) {
            // Scope-limiting must always happen before label matching!
            exactBuilder.descendantsOf(aConceptScope);
        }
        
        // Collect exact matches - although exact matches are theoretically contained in the
        // set of containing matches, due to the ranking performed by the KB/FTS, we might
        // not actually see the exact matches within the first N results. So we query for
        // the exact matches separately to ensure we have them.
        // Mind, we use the query and the mention text here - of course we don't only want 
        // exact matches of the query but also of the mention :)
        String[] exactLabels = asList(aQuery, aMention).stream()
                .filter(StringUtils::isNotBlank)
                .toArray(String[]::new);
        
        if (exactLabels.length > 0) {
            exactBuilder.withLabelMatchingExactlyAnyOf(exactLabels);
            
            exactBuilder
                    .retrieveLabel()
                    .retrieveDescription();
    
            List<KBHandle> exactMatches;
            if (aKB.isReadOnly()) {
                exactMatches = kbService.listHandlesCaching(aKB, exactBuilder, true);
            }
            else {
                exactMatches = kbService.read(aKB, conn -> exactBuilder.asHandles(conn, true));
            }
            
            
            log.debug("Found [{}] candidates exactly matching {}",
                    exactMatches.size(), asList(exactLabels));
    
            result.addAll(exactMatches);
        }

        // Next we also do a "starting with" search - but only if the user's query is longer than
        // the threshold - this is because for short queries, we'd get way too many results which
        // would be slow - and also the results would likely not be very accurate
        if (aQuery != null && aQuery.trim().length() >= threshold) {
            SPARQLQueryPrimaryConditions startingWithBuilder = newQueryBuilder(aValueType, aKB);
            
            if (aConceptScope != null) {
                // Scope-limiting must always happen before label matching!
                startingWithBuilder.descendantsOf(aConceptScope);
            }
            
            // Collect matches starting with the query - this is the main driver for the
            // auto-complete functionality
            startingWithBuilder.withLabelStartingWith(aQuery);
            
            startingWithBuilder
                    .retrieveLabel()
                    .retrieveDescription();
            
            List<KBHandle> startingWithMatches;
            if (aKB.isReadOnly()) {
                startingWithMatches = kbService.listHandlesCaching(aKB, startingWithBuilder, true);
            }
            else {
                startingWithMatches = kbService.read(aKB,
                    conn -> startingWithBuilder.asHandles(conn, true));
            }
                        
            log.debug("Found [{}] candidates starting with [{}]]",
                    startingWithMatches.size(), aQuery);            
            
            result.addAll(startingWithMatches);
        }
        
        // Finally, we use the query and mention also for a "containing" search - but only if they
        // are longer than the threshold. Again, for very short query/mention, we'd otherwise get 
        // way too many matches, being slow and not accurate.
        String[] longLabels = asList(aQuery, aMention).stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim())
                .filter(s -> s.length() >= threshold)
                .toArray(String[]::new);
        
        if (longLabels.length > 0) {
            // Collect containing matches
            SPARQLQueryPrimaryConditions matchingBuilder = newQueryBuilder(aValueType, aKB);

            if (aConceptScope != null) {
                // Scope-limiting must always happen before label matching!
                matchingBuilder.descendantsOf(aConceptScope);
            }
            
            matchingBuilder.withLabelMatchingAnyOf(longLabels);
            
            matchingBuilder
                    .retrieveLabel()
                    .retrieveDescription();
            
            List<KBHandle> matchingMatches;
            if (aKB.isReadOnly()) {
                matchingMatches = kbService.listHandlesCaching(aKB, matchingBuilder, true);
            }
            else {
                matchingMatches = kbService.read(aKB,
                    conn -> matchingBuilder.asHandles(conn, true));
            }
            
            log.info("Found [{}] candidates using matching {}",
                    matchingMatches.size(), asList(longLabels));
            
            result.addAll(matchingMatches);
        }

        log.debug("Generated [{}] candidates in {}ms", result.size(),
                currentTimeMillis() - startTime);

        return result;
    }
    
    @Override
    public List<KBHandle> disambiguate(KnowledgeBase aKB, String aConceptScope,
            ConceptFeatureValueType aValueType, String aQuery, String aMention,
            int aMentionBeginOffset, CAS aCas)
    {
        Set<KBHandle> candidates = generateCandidates(aKB, aConceptScope, aValueType, aQuery,
                aMention);
        return rankCandidates(aQuery, aMention, candidates, aCas, aMentionBeginOffset);
    }

    @Override
    public List<KBHandle> rankCandidates(String aQuery, String aMention, Set<KBHandle> aCandidates,
            CAS aCas, int aBegin)
    {
        long startTime = currentTimeMillis();
        
        List<KBHandle> results = ranker.rank(aQuery, aMention, aCandidates, aCas, aBegin);
        
        int rank = 1;
        for (KBHandle handle : results) {
            handle.setRank(rank);
            rank++;
        }
         
        log.debug("Ranked [{}] candidates for mention [{}] and query [{}] in [{}] ms",
                 results.size(), aMention, aQuery, currentTimeMillis() - startTime);
         
        return results;
    }

    @Override
    public List<KBHandle> getLinkingInstancesInKBScope(String aRepositoryId, String aConceptScope,
            ConceptFeatureValueType aValueType, String aQuery, String aMention,
            int aMentionBeginOffset, CAS aCas, Project aProject)
    {
        // Sanitize query by removing typical wildcard characters
        String query = aQuery.replaceAll("[*?]", "").trim();
        
        // Determine which knowledge bases to query
        List<KnowledgeBase> knowledgeBases = new ArrayList<>();
        if (aRepositoryId != null) {
            kbService.getKnowledgeBaseById(aProject, aRepositoryId)
                    .filter(KnowledgeBase::isEnabled)
                    .ifPresent(knowledgeBases::add);
        }
        else {
            knowledgeBases.addAll(kbService.getEnabledKnowledgeBases(aProject));
        }
        
        // Query the knowledge bases for candidates
        Set<KBHandle> candidates = new HashSet<>();
        for (KnowledgeBase kb : knowledgeBases) {
            candidates.addAll(generateCandidates(kb, aConceptScope, aValueType, query, aMention));
        }
        
        // Rank the candidates and return them
        return rankCandidates(query, aMention, candidates, aCas, aMentionBeginOffset);
    }

    /**
     * Find KB items (classes and instances) matching the given query.
     */
    @Override
    public List<KBHandle> searchItems(KnowledgeBase aKB, String aQuery)
    {
        return disambiguate(aKB, null, ConceptFeatureValueType.ANY_OBJECT, aQuery, null, 0, null);
    }
}
