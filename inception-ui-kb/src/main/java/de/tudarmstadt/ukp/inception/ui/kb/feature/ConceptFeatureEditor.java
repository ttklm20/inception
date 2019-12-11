/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.ui.kb.feature;

import static de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaBehavior.visibleWhen;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.apache.commons.lang3.StringUtils.containsIgnoreCase;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static org.apache.wicket.markup.head.JavaScriptHeaderItem.forReference;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.uima.cas.CAS;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.core.request.handler.IPartialPageRequestHandler;
import org.apache.wicket.feedback.IFeedback;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.form.FormComponent;
import org.apache.wicket.markup.html.link.ExternalLink;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.clarin.webanno.api.annotation.action.AnnotationActionHandler;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.FeatureSupport;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.editor.FeatureEditor;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.editor.KendoChoiceDescriptionScriptReference;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.keybindings.KeyBindingsPanel;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.model.AnnotatorState;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.model.FeatureState;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.inception.conceptlinking.service.ConceptLinkingService;
import de.tudarmstadt.ukp.inception.kb.ConceptFeatureTraits;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.ui.kb.IriInfoBadge;

/**
 * Component for editing knowledge-base-related features on annotations.
 */
public class ConceptFeatureEditor
    extends FeatureEditor
{
    private static final Logger LOG = LoggerFactory.getLogger(ConceptFeatureEditor.class);
    
    private static final long serialVersionUID = 7763348613632105600L;

    private FormComponent focusComponent;
    private IriInfoBadge iriBadge;
    private ExternalLink openIriLink;

    private @SpringBean KnowledgeBaseService kbService;
    private @SpringBean FeatureSupportRegistry featureSupportRegistry;
    private @SpringBean ConceptLinkingService clService;

    public ConceptFeatureEditor(String aId, MarkupContainer aItem, IModel<FeatureState> aModel,
            IModel<AnnotatorState> aStateModel, AnnotationActionHandler aHandler)
    {
        super(aId, aItem, new CompoundPropertyModel<>(aModel));
        
        IModel<String> iriModel = LoadableDetachableModel.of(this::iriTooltipValue);
        
        iriBadge = new IriInfoBadge("iriInfoBadge", iriModel);
        iriBadge.add(visibleWhen(() -> isNotBlank(iriBadge.getModelObject())));
        add(iriBadge);
        
        openIriLink = new ExternalLink("openIri", iriModel);
        openIriLink.add(visibleWhen(() -> isNotBlank(iriBadge.getModelObject())));
        add(openIriLink);

        add(new DisabledKBWarning("disabledKBWarning", Model.of(getModelObject().feature)));

        add(focusComponent = new KnowledgeBaseItemAutoCompleteField(MID_VALUE, _query -> 
                getCandidates(aStateModel, aHandler, _query)));
        
        AnnotationFeature feat = getModelObject().feature;
        ConceptFeatureTraits traits = readFeatureTraits(feat);
        
        add(new KeyBindingsPanel("keyBindings", () -> traits.getKeyBindings(), aModel, aHandler)
                // The key bindings are only visible when the label is also enabled, i.e. when the
                // editor is used in a "normal" context and not e.g. in the keybindings 
                // configuration panel
                .add(visibleWhen(() -> getLabelComponent().isVisible())));
    }

    @Override
    public void renderHead(IHeaderResponse aResponse)
    {
        super.renderHead(aResponse);

        aResponse.render(forReference(KendoChoiceDescriptionScriptReference.get()));
    }
    
    private String iriTooltipValue()
    {
        return getModel().map(FeatureState::getValue)
                .map(value -> (KBHandle) value)
                .map(KBHandle::getIdentifier)
                .orElse("")
                .getObject();
    }

    private List<KBHandle> getCandidates(IModel<AnnotatorState> aStateModel,
            AnnotationActionHandler aHandler, String aInput)
    {
        if (aInput == null) {
            return emptyList();
        }

        String input = aInput;
        
        // Extract filter on the description
        final String descriptionFilter;
        if (input.contains("::")) {
            descriptionFilter = substringAfter(input , "::");
            input = substringBefore(input , "::");
        }
        else {
            descriptionFilter = null;
        }
        
        // Extract exact match filter on the query
        boolean labelFilter = false;
        String trimmedInput = input.trim();
        if (trimmedInput.length() > 2 && trimmedInput.startsWith("\"")
                && trimmedInput.endsWith("\"")) {
            input = StringUtils.substring(trimmedInput, 1, -1);
            labelFilter = true;
        }
        
        final String finalInput = input;
        
        List<KBHandle> choices;
        try {
            AnnotationFeature feat = getModelObject().feature;

            ConceptFeatureTraits traits = readFeatureTraits(feat);
            String repoId = traits.getRepositoryId();
            // Check if kb is actually enabled
            if (!(repoId == null
                || kbService.isKnowledgeBaseEnabled(feat.getProject(), repoId)))
            {
                return Collections.emptyList();
            }

            // If there is a selection, we try obtaining its text from the CAS and use it as an
            // additional item in the query. Note that there is not always a mention, e.g. when the
            // feature is used in a document-level annotations.
            CAS cas = aHandler != null ? aHandler.getEditorCas() : null;
            String mention = aStateModel != null ? aStateModel.getObject().getSelection().getText()
                    : null;
            int mentionBegin = aStateModel != null
                    ? aStateModel.getObject().getSelection().getBegin()
                    : -1;
            
            choices = clService.getLinkingInstancesInKBScope(traits.getRepositoryId(),
                    traits.getScope(), traits.getAllowedValueType(), finalInput, mention,
                    mentionBegin, cas, feat.getProject());
        }
        catch (Exception e) {
            choices = asList(new KBHandle("http://ERROR", "ERROR", e.getMessage(), "en"));
            error("An error occurred while retrieving entity candidates: " + e.getMessage());
            LOG.error("An error occurred while retrieving entity candidates", e);
            RequestCycle.get()
                .find(IPartialPageRequestHandler.class)
                .ifPresent(target -> target.addChildren(getPage(), IFeedback.class));
        }

        if (labelFilter) {
            choices = choices.stream()
                    .filter(kb -> containsIgnoreCase(kb.getUiLabel(), finalInput))
                    .collect(Collectors.toList());
        }

        if (isNotBlank(descriptionFilter)) {
            choices = choices.stream()
                    .filter(kb -> containsIgnoreCase(kb.getDescription(), descriptionFilter))
                    .collect(Collectors.toList());
        }
        
        return choices;
    }

    private ConceptFeatureTraits readFeatureTraits(AnnotationFeature aAnnotationFeature)
    {
        FeatureSupport<ConceptFeatureTraits> fs = featureSupportRegistry
                .getFeatureSupport(aAnnotationFeature);
        ConceptFeatureTraits traits = fs.readTraits(aAnnotationFeature);
        return traits;
    }

    @Override
    public FormComponent getFocusComponent()
    {
        return focusComponent;
    }
}
