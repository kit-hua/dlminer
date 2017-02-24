package io.dlminer.learn;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.dlminer.graph.ALCNode;
import io.dlminer.graph.CEdge;
import io.dlminer.graph.ELNode;
import io.dlminer.graph.Expansion;
import io.dlminer.graph.OnlyEdge;
import io.dlminer.graph.SomeEdge;
import io.dlminer.main.DLMinerOutputI;
import io.dlminer.ont.DepthMetric;
import io.dlminer.ont.InstanceChecker;
import io.dlminer.ont.LengthMetric;
import io.dlminer.ont.Logic;
import io.dlminer.ont.OWLObjectPropertyChain;
import io.dlminer.ont.OntologyHandler;
import io.dlminer.print.Out;
import io.dlminer.refine.ALCOperator;
import io.dlminer.refine.LearnerOperator;
import io.dlminer.sort.ConceptLengthComparator;
import io.dlminer.sort.NodeLengthComparator;
import io.dlminer.sort.SortingOrder;


public class ConceptBuilder {	
	
	private OntologyHandler handler;		
	private OWLDataFactory factory;
		
	private List<OWLClass> classes;
	private List<OWLObjectProperty> properties;
	private Set<OWLEntity> signature;
	
	// class maps
	private Map<Language, List<OWLClass>> languageClassMap;
	private Map<OWLClass, OWLClassExpression> classExpressionMap;
	private Map<OWLClassExpression, OWLClass> expressionClassMap;
	private Map<OWLClassExpression, OWLAxiom> classExpressionDefinitionMap;
	
	// role maps
	private Map<Language, List<OWLObjectProperty>> languageRoleMap;
	private Map<OWLObjectProperty, OWLObjectPropertyExpression> roleExpressionMap;
	private Map<OWLObjectPropertyExpression, OWLObjectProperty> expressionRoleMap;
	private Map<OWLObjectPropertyExpression, OWLAxiom> roleExpressionDefinitionMap;
		
	
	// class-instance maps
	private Map<OWLClassExpression, Set<OWLNamedIndividual>> expressionInstanceMap;
	private Map<OWLClass, Set<OWLNamedIndividual>> classInstanceMap;
	private Map<ALCNode, Set<OWLNamedIndividual>> nodeInstanceMap;
	private Map<ALCNode, List<Expansion>> nodeExpansionMap;
	private Map<ALCNode, List<Expansion>> nodeClusterMap;
	private Map<OWLClassExpression, Double> expressionTimeMap;
	
	// role-instance maps
	private Map<OWLObjectProperty, Set<List<OWLNamedIndividual>>> roleInstanceMap;
	
	// graph, reasoners
	private Map<OWLNamedIndividual, ELNode> aboxGraph;
	private List<Expansion> expansions;	
	private InstanceChecker instanceChecker;	
	private OWLReasoner reasoner;
		
	// parameters
	private int maxRoleDepth;
	
	private int minSupport;
	
	private int maxConceptLength;
	
	private int maxConceptNumber;
	
	private int beamSize;
	
	
	private boolean checkDisjointness;	
	private boolean useDisjunction;
	private boolean useNegation;
	private boolean useUniversalRestriction;
	
	
	private Logic logic;		
	
	private ALCOperator operator;
	
	
	private OWLClass positiveClass;	
	private OWLClass negativeClass;
	
	private ALCNode positiveNode;	
	private ALCNode negativeNode;
	
		
		
	public ConceptBuilder(OntologyHandler handler, OWLReasoner reasoner,
			int roleDepth, int minSupport, 
			Logic logic) {
		initAttributes(handler, reasoner, roleDepth, minSupport, logic);				
	}
			
		
	
	private void initAttributes(OntologyHandler handler, OWLReasoner reasoner,
			int roleDepth, int minSupport, Logic logic) {
		this.handler = handler;
		factory = handler.getDataFactory();
		this.reasoner = reasoner;		
		signature = handler.getSignature();
		signature.addAll(reasoner.getRootOntology().getSignature());
		this.maxRoleDepth = roleDepth;
		this.minSupport = minSupport;
		this.logic = logic;		
	}

		
	public void init() {
		// initialise maps
		initCollections();
		Out.p("\nInitialising the refinement operator");
		// initialise the refinement operator
		initRefinementOperator();
		// materialise the ABox
		Out.p("\nMaterialising the ABox");
		handler.materialise(operator);				
		// initialise the instance checker
		Out.p("\nInitialising the instance checker");
		initInstanceChecker();
		// initialise expansions
		buildExpansions();
	}

				
	
	public void setPositiveClass(OWLClass positiveClass) {
		this.positiveClass = positiveClass;
	}


	/**
	 * @return the positiveClass
	 */
	public OWLClass getPositiveClass() {
		return positiveClass;
	}



	public void setNegativeClass(OWLClass negativeClass) {
		this.negativeClass = negativeClass;
	}



	/**
	 * @return the negativeClass
	 */
	public OWLClass getNegativeClass() {
		return negativeClass;
	}



	private void initRefinementOperator() {
		Set<OWLClass> cls = new HashSet<>(classes);
		Set<OWLObjectProperty> props = new HashSet<>(properties);		
		if (logic.equals(Logic.EL)) {	
			operator = new ALCOperator(reasoner, cls, props, 
					maxConceptLength, maxRoleDepth, checkDisjointness);
		} else {
			operator = new ALCOperator(reasoner, cls, props, 
					maxConceptLength, maxRoleDepth, checkDisjointness,
					useNegation, useUniversalRestriction, useDisjunction);
		}
	}


	private void buildExpansions() {
		Out.p("\nBuilding the ABox graph");		
		aboxGraph = buildABoxGraphFromAssertions();
		// build an expansion per individual	
		Out.p("\nBuilding the expansions for individuals");
		expansions = buildIndividualExpansions(aboxGraph);				
		// normalise
		Out.p("\nNormalising the trees");
		for (Expansion n : expansions) {
			n.normalise();
		}
		// find unique representatives
		Out.p("\nClustering expansions");
		instanceChecker.clusterExpansions(expansions);
	}


	public void buildConcepts(int maxConceptLength, 
			int beamSize, int maxConceptNumber) {
		this.maxConceptLength = maxConceptLength;		
		this.beamSize = beamSize;
		this.maxConceptNumber = maxConceptNumber;
		operator.setMaxLength(maxConceptLength);		
		// build expressions		
		buildClassExpressions();
		buildClassDefinitions();
	}
	
	
	
	public void buildRoles() {
		buildRoleExpressions();
		buildRoleDefinitions();
	}

	
	
	public void build(List<Language> languages) {
		// build expressions
		buildForLanguages(languages);			
	}
	
	
	
	private void initCollections() {		
		// init classes and properties
		classes = new ArrayList<>();
		properties = new ArrayList<>();
		for (OWLEntity en : signature) {
			if (en.isOWLClass()) {
				OWLClass cl = (OWLClass)en;
				if (!cl.isOWLNothing()) {
					classes.add(cl);
				}
			} else if (en.isOWLObjectProperty()) {
				OWLObjectProperty prop = (OWLObjectProperty)en;
				if (!prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty()) {
					properties.add(prop);
				}
			} else if (en.isOWLDataProperty()) {
				// no data properties yet
			}
		}				
		// init class maps
		languageClassMap = new LinkedHashMap<>();
		classExpressionMap = new LinkedHashMap<>();
		expressionClassMap = new LinkedHashMap<>();
		classExpressionDefinitionMap = new LinkedHashMap<>();
		// init role maps
		languageRoleMap = new LinkedHashMap<>();
		roleExpressionMap = new LinkedHashMap<>();
		expressionRoleMap = new LinkedHashMap<>();
		roleExpressionDefinitionMap = new LinkedHashMap<>();
		// init class-instance maps
		expressionInstanceMap = new LinkedHashMap<>();
		classInstanceMap = new LinkedHashMap<>();
		nodeInstanceMap = new LinkedHashMap<>();
		nodeExpansionMap = new LinkedHashMap<>();
		nodeClusterMap = new LinkedHashMap<>();
		roleInstanceMap = new LinkedHashMap<>();
		// time
		expressionTimeMap = new LinkedHashMap<>();
	}
	
	
	
	
	
	
	
	
	
	
	private void initInstanceChecker() {		
		instanceChecker = new InstanceChecker(handler);		
	}
	
	
	
	
	
		
	private void buildForLanguages(List<Language> languages) {
		for (Language lang : languages) {
			buildForLanguage(lang);
		}
	}
	
	
	public void buildForLanguage(Language lang) {
		buildExpressionsForLanguage(lang);
		buildDefinitionsForLanguage(lang);
	}	


	private void buildExpressionsForLanguage(Language lang) {				
		buildClassExpressionForLanguage(classes, lang);
		buildRoleExpressionForLanguage(properties, lang);		
	}
	
	
	private void buildDefinitionsForLanguage(Language lang) {
		if (languageClassMap.containsKey(lang)) {
			for (OWLClass cl : languageClassMap.get(lang)) {
				buildClassDefinition(cl);
			}
		} else
		if (languageRoleMap.containsKey(lang)) {
			for (OWLObjectProperty prop : languageRoleMap.get(lang)) {
				buildRoleDefinition(prop);
			}
		}
	}
	
	
	
	public void buildClassDefinitions() {
		for (OWLClass cl : classExpressionMap.keySet()) {
			buildClassDefinition(cl);
		}
	}
	
	
	private void buildRoleDefinitions() {
		for (OWLObjectProperty prop : roleExpressionMap.keySet()) {
			buildRoleDefinition(prop);
		}
	}
	
	
	private void buildClassDefinition(OWLClass cl) {
		// do not create definitions for atomics
		if (!classes.contains(cl)) {
			OWLClassExpression expr = classExpressionMap.get(cl);
			if (!classExpressionDefinitionMap.containsKey(expr)) {
				OWLAxiom defAxiom = factory.getOWLEquivalentClassesAxiom(cl, expr);
				classExpressionDefinitionMap.put(expr, defAxiom);
			}
		}
	}
	
	
	
	private void buildRoleDefinition(OWLObjectProperty prop) {
		// do not create definitions for atomics
		if (!properties.contains(prop)) {
			OWLObjectPropertyExpression expr = roleExpressionMap.get(prop);
			// do not create definitions for chains
			if (!(expr instanceof OWLObjectPropertyChain) 
					&& !roleExpressionDefinitionMap.containsKey(expr)) {
				OWLAxiom defAxiom = factory.getOWLEquivalentObjectPropertiesAxiom(prop, expr);
				roleExpressionDefinitionMap.put(expr, defAxiom);
			}
		}
	}
	
	
	private List<OWLClass> generateClasses(Collection<OWLClassExpression> exprs) {
		List<OWLClass> cls = new ArrayList<>(exprs.size());
		for (OWLClassExpression expr : exprs) {
			// generate a definition
			OWLClass cl = handler.generateClass();
			cls.add(cl);			
		}
		return cls;
	}
	
	private List<OWLObjectProperty> generateProperties(
			List<OWLObjectPropertyExpression> exprs) {
		List<OWLObjectProperty> props = new ArrayList<>(exprs.size());
		for (OWLObjectPropertyExpression expr : exprs) {
			// generate a definition
			OWLObjectProperty prop = handler.generateProperty();
			props.add(prop);	
		}		
		return props;
	}
	
		
	private void addClassExpressionMappings(
			List<OWLClass> cls, List<? extends OWLClassExpression> exprs) {
		for (int i=0; i<cls.size(); i++) {
			classExpressionMap.put(cls.get(i), exprs.get(i));
			expressionClassMap.put(exprs.get(i), cls.get(i));
		}
	}
	
	private void addRoleExpressionMappings(
			List<OWLObjectProperty> props, List<? extends OWLObjectPropertyExpression> exprs) {
		for (int i=0; i<props.size(); i++) {
			roleExpressionMap.put(props.get(i), exprs.get(i));
			expressionRoleMap.put(exprs.get(i), props.get(i));
		}
	}
	
	
	
	private void buildClassExpressionForLanguage(List<? extends OWLClassExpression> As, Language lang) {		
		// atomic classes
		if (lang.equals(Language.A)) {
			languageClassMap.put(Language.A, classes);
			addClassExpressionMappings(classes, As);
		} else
		// add subconcepts if required (all or only for the signature)		
		if (lang.equals(Language.C)) {			
			List<OWLClassExpression> subcons = buildSubConceptsForSignature();
			if (subcons != null && !subcons.isEmpty()) {
				List<OWLClass> subconCls = generateClasses(subcons);
				addClassExpressionMappings(subconCls, subcons);			
				languageClassMap.put(Language.C, subconCls);				
			}
		} else
		if (lang.equals(Language.NOT_C)) {
			List<OWLClassExpression> exprs = generateNotAs(As);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.NOT_C, cls);
		} else
		if (lang.equals(Language.C_AND_D)) {
			List<OWLClassExpression> exprs = generateAandBs(As, As);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.C_AND_D, cls);				
		} else
		if (lang.equals(Language.C_AND_NOT_D)) {
			List<OWLClassExpression> notAs = generateNotAs(As);
			List<OWLClassExpression> exprs = generateAandBs(As, notAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.C_AND_NOT_D, cls);
		} else
		if (lang.equals(Language.R_SOME_C)) {
			List<OWLClassExpression> exprs = generateRsomeAs(As);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.R_SOME_C, cls);
		} else
		if (lang.equals(Language.INV_R_SOME_C)) {
			List<OWLObjectPropertyExpression> invRs = generateInverseRs();
			List<OWLClassExpression> exprs = generateRsomeAs(invRs, As);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.INV_R_SOME_C, cls);
		} else
		if (lang.equals(Language.R_SOME_NOT_C)) {
			List<OWLClassExpression> notAs = generateNotAs(As);
			List<OWLClassExpression> exprs = generateRsomeAs(notAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.R_SOME_NOT_C, cls);
		} else
		if (lang.equals(Language.C_AND_R_SOME_D)) {
			List<OWLClassExpression> RsomeAs = generateRsomeAs(As);
			List<OWLClassExpression> exprs = generateAandBs(As, RsomeAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.C_AND_R_SOME_D, cls);
		} else
		if (lang.equals(Language.NOT_C_AND_R_SOME_D)) {
			List<OWLClassExpression> notAs = generateNotAs(As);
			List<OWLClassExpression> RsomeAs = generateRsomeAs(As);
			List<OWLClassExpression> exprs = generateAandBs(notAs, RsomeAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.NOT_C_AND_R_SOME_D, cls);
		} else
		if (lang.equals(Language.R_SOME_C_AND_D)) {
			List<OWLClassExpression> AandBs = generateAandBs(As, As);
			List<OWLClassExpression> exprs = generateRsomeAs(AandBs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.R_SOME_C_AND_D, cls);
		} else
		if (lang.equals(Language.R_SOME_S_SOME_C)) {
			List<OWLClassExpression> RsomeAs = generateRsomeAs(As);
			List<OWLClassExpression> exprs = generateRsomeAs(RsomeAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.R_SOME_S_SOME_C, cls);
		} else 
		if (lang.equals(Language.R_ONLY_C)) {
			List<OWLClassExpression> exprs = generateRonlyAs(As);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.R_ONLY_C, cls);
		} else			
		if (lang.equals(Language.R_ONLY_NOT_C)) {
			List<OWLClassExpression> notAs = generateNotAs(As);
			List<OWLClassExpression> exprs = generateRonlyAs(notAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.R_ONLY_NOT_C, cls);
		} else
		if (lang.equals(Language.C_OR_D)) {
			List<OWLClassExpression> exprs = generateAorBs(As, As);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.C_OR_D, cls);
		} else		
		if (lang.equals(Language.C_OR_NOT_D)) {
			List<OWLClassExpression> notAs = generateNotAs(As);
			List<OWLClassExpression> exprs = generateAorBs(As, notAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.C_OR_NOT_D, cls);
		} else
		if (lang.equals(Language.C_OR_R_SOME_D)) {
			List<OWLClassExpression> RsomeAs = generateRsomeAs(As);
			List<OWLClassExpression> exprs = generateAorBs(As, RsomeAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.C_OR_R_SOME_D, cls);
		} else
		if (lang.equals(Language.NOT_C_OR_R_SOME_D)) {
			List<OWLClassExpression> notAs = generateNotAs(As);
			List<OWLClassExpression> RsomeAs = generateRsomeAs(As);
			List<OWLClassExpression> exprs = generateAorBs(notAs, RsomeAs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.NOT_C_OR_R_SOME_D, cls);
		} else
		if (lang.equals(Language.R_SOME_C_OR_D)) {
			List<OWLClassExpression> AorBs = generateAorBs(As, As);
			List<OWLClassExpression> exprs = generateRsomeAs(AorBs);
			List<OWLClass> cls = generateClasses(exprs);
			addClassExpressionMappings(cls, exprs);
			languageClassMap.put(Language.R_SOME_C_OR_D, cls);
		} else
		if (lang.equals(Language.CDL)) {
			// do nothing here			
		} else
		if (lang.equals(Language.DATA_C)) {
			// do nothing here			
		}
	}
	
	


	private void buildRoleExpressionForLanguage(List<? extends OWLObjectPropertyExpression> Rs, Language lang) {
		// atomic roles
		if (lang.equals(Language.R)) {
			languageRoleMap.put(Language.R, properties);
			addRoleExpressionMappings(properties, Rs);
		} else
		if (lang.equals(Language.INV_R)) {
			List<OWLObjectPropertyExpression> exprs = generateInverseRs(Rs);
			List<OWLObjectProperty> props = generateProperties(exprs);
			addRoleExpressionMappings(props, exprs);
			languageRoleMap.put(Language.INV_R, props);
		} else
		if (lang.equals(Language.R_CHAIN_S)) {
			List<OWLObjectPropertyExpression> exprs = generateRchainSs(Rs, Rs);
			List<OWLObjectProperty> props = generateProperties(exprs);
			addRoleExpressionMappings(props, exprs);
			languageRoleMap.put(Language.R_CHAIN_S, props);	
		} else
		if (lang.equals(Language.INV_R_CHAIN_S)) {			
			List<OWLObjectPropertyExpression> invRs = generateInverseRs(Rs);
			List<OWLObjectPropertyExpression> exprs = generateRchainSs(invRs, Rs);
			List<OWLObjectProperty> props = generateProperties(exprs);
			addRoleExpressionMappings(props, exprs);
			languageRoleMap.put(Language.INV_R_CHAIN_S, props);	
		}
	}
		


	/*public void learnConceptDefinitions(Map<OWLClass, Set<OWLNamedIndividual>> posMap) {
		List<OWLClassExpression> exprs = generateCDs(posMap);
		List<OWLClass> cls = generateClasses(exprs);
		addClassExpressionMappings(cls, exprs);
		languageClassMap.put(Language.CDL, cls);
		buildClassDefinitions();
	}*/
	
		
	
	
	
	private void buildRoleExpressions() {
		if (maxRoleDepth > 0) {
			if (!languageRoleMap.containsKey(Language.R)) {
				buildRoleExpressionForLanguage(properties, Language.R);
			}
			if (!languageRoleMap.containsKey(Language.INV_R)) {
				buildRoleExpressionForLanguage(properties, Language.INV_R);
			}
			if (!languageRoleMap.containsKey(Language.R_CHAIN_S)) {
				buildRoleExpressionForLanguage(properties, Language.R_CHAIN_S);
			}
			generateAndMapDataRoles();
		}
	}
	
	
	private void generateAndMapDataRoles() {
		Map<OWLObjectPropertyExpression, Set<List<OWLNamedIndividual>>> 
			roleExprInstMap = new HashMap<>();		
		// the method calling order matters		
		fillInstanceMapForRoles(roleExprInstMap);
		// this one works once the previous one is done
		fillInstanceMapForInverseRoles(roleExprInstMap);		
		fillInstanceMapForRoleChains(languageRoleMap.get(Language.R_CHAIN_S), roleExprInstMap);		
		// keep only non-empty expressions
		for (OWLObjectProperty prop : roleExpressionMap.keySet()) {
			OWLObjectPropertyExpression expr = roleExpressionMap.get(prop);
			Set<List<OWLNamedIndividual>> insts = roleExprInstMap.get(expr);
			if (insts != null && !insts.isEmpty()) {
				roleInstanceMap.put(prop, insts);
			}
		}
	}
	
			
	private void buildRefinements(LearnerOperator operator) {
		// root		
		OWLClassExpression root = factory.getOWLThing();
		expressionInstanceMap.put(root, handler.getIndividuals());		
		// loop
		LinkedList<OWLClassExpression> candidates = new LinkedList<>();
		Set<OWLClassExpression> processed = new HashSet<>();
		candidates.add(root);
		processed.add(root);
		int iters = 0;		
		loop:
			while (!candidates.isEmpty()) {
				OWLClassExpression current = candidates.pollFirst();			
				// generate all extensions of length+2
				Set<OWLClassExpression> extensions = operator.refine(current);		
				// beam
				List<OWLClassExpression> beam = new LinkedList<>();				
				// evaluate extensions			
				for (OWLClassExpression extension : extensions) {
					try {
						if (DepthMetric.depth(extension) <= maxRoleDepth
								&& LengthMetric.length(extension) <= maxConceptLength
								&& !processed.contains(extension)
								&& !isEquivalentTo(extension, current)) {
							Set<OWLNamedIndividual> instances = null;						
							instances = reasoner.getInstances(extension, false).getFlattened();
							if (instances != null && instances.size() >= minSupport) {
								beam.add(extension);
								expressionInstanceMap.put(extension, instances);																					
							}
						}
					} catch (Exception e) {
						Out.p(e + DLMinerOutputI.CONCEPT_BUILDING_ERROR);
					}
				}
				processed.addAll(extensions);
				// find beam concepts and add them to candidates
				Collections.sort(beam, new ConceptLengthComparator(SortingOrder.ASC));
				if (beam.size() <= beamSize) {
					candidates.addAll(beam);
				} else {
					candidates.addAll(beam.subList(0, beamSize));
				}
				// debug		
				Out.p("iterations=" + (++iters) 
						+ " concepts=" + expressionInstanceMap.size()
						+ " candidates=" + candidates.size()
						+ " extensions=" + extensions.size()
						+ " current=" + current);			
			}
		Out.p("\nDL-Refinement has terminated");
	}


	
		
	private boolean isEquivalentTo(OWLClassExpression cl1, 
			OWLClassExpression cl2) throws Exception {		
		OWLAxiom axiom1 = factory.getOWLSubClassOfAxiom(cl1, cl2);
		OWLAxiom axiom2 = factory.getOWLSubClassOfAxiom(cl2, cl1);
		return reasoner.isEntailed(axiom1) && reasoner.isEntailed(axiom2);
	}


//	private void buildStartClasses(Language... startLanguages) {
//		startClasses = new HashSet<OWLClassExpression>(classes);
//		for (Language lang : startLanguages) {
//			initStartLanguage(lang);
//		}
//	}
//	
//	
//	private void buildStartClasses() {
//		startClasses = new HashSet<OWLClassExpression>();		
//		Map<OWLClassExpression, Set<OWLNamedIndividual>> 
//			clasIndMap = instanceChecker.getClasIndMap();
//		for (OWLClassExpression cl : clasIndMap.keySet()) {
//			if (clasIndMap.get(cl).size() >= minSupport) {
//				startClasses.add(cl);
//			}
//		}
//	}
	
	
	private void initStartLanguage(Language lang) {
		if (!lang.equals(Language.A)
				&& !lang.equals(Language.R)
				&& !lang.equals(Language.INV_R)
				&& !lang.equals(Language.R_CHAIN_S)
				&& !lang.equals(Language.INV_R_CHAIN_S)) {
//			Out.p("\nInitialising start class expressions");
			buildForLanguage(lang);
//			for (OWLClass cl : languageClassMap.get(lang)) {
//				startClasses.add(classExpressionMap.get(cl));
//			}		
			// initialise the reasoner on complex class expressions if necessary			
//			addDefinitions(lang, handler);
//			reasoner.flush();
//			reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY,
//					InferenceType.OBJECT_PROPERTY_HIERARCHY,
//					InferenceType.CLASS_ASSERTIONS, 
//					InferenceType.OBJECT_PROPERTY_ASSERTIONS);
		}
	}
	
	
	
	private void buildClassExpressions() {
		buildALCConceptsOptimised();
		// generate encoding classes
		generateAndMapDataConcepts();
	}
	
	
	
	private void buildALCConceptsOptimised() {
		// build concepts		
		aprioriALCOptimised();
		// remove redundant concepts
		removeRedundantNodesExp();
		// convert to expressionInstanceMap
		convertToExpressionInstanceMapExp();	
	}
		


	/*private void buildALCConcepts() {
		// build concepts		
		aprioriALC();
		// remove redundant concepts
		removeRedundantNodesInd();
		// convert to expressionInstanceMap
		convertToExpressionInstanceMapInd();
	}*/
	
	
	
	private void removeRedundantNodesExp() {
		Out.p("\nRemoving redundant concepts");
		Set<ALCNode> redunNodes = new HashSet<>();
		for (ALCNode node : nodeClusterMap.keySet()) {
			if (operator.isRedundantNode(node)) {
				redunNodes.add(node);
			}
		}
		for (ALCNode node : redunNodes) {
			nodeClusterMap.remove(node);
		}
		Out.p("\n" + redunNodes.size() + " concepts are redundant and removed");
	}
	
	
	
	private boolean isRedundantSameInstances(ALCNode node1) {		
		for (ALCNode node2 : nodeClusterMap.keySet()) {
			if (node1.equals(node2)) {
				continue;
			}
			if (node1.length() <= node2.length()) {
				continue;
			}
			if (node1.isMoreSpecificThan(node2) || node2.isMoreSpecificThan(node1)) {
				// same instances
				if (nodeClusterMap.get(node1).equals(
						nodeClusterMap.get(node2))) {
					return true;					
				}
			}
		}
		return false;
	}
	
	
	
	/*private void removeRedundantNodesInd() {
		Set<ALCNode> redunNodes = new HashSet<>();
		for (ALCNode node : nodeInstanceMap.keySet()) {
			if (operator.isRedundantNode(node)) {
				redunNodes.add(node);
			}
		}
		for (ALCNode node : redunNodes) {
			nodeInstanceMap.remove(node);
		}
		Out.p("\n" + redunNodes.size() + " concepts are redundant and removed");
	}*/


	
	/*private void aprioriALC() {
		PriorityQueue<ALCNode> candidates = new PriorityQueue<>(100, 
				new NodeLengthComparator(SortingOrder.ASC));
		Set<ALCNode> processed = new HashSet<>();
		if (nodeInstanceMap.isEmpty()) {
			// the first run
			ALCNode rootNode = processNode(factory.getOWLThing());
			candidates.add(rootNode);
			processed.add(rootNode);
			// if CDL
			if (positiveClass != null && negativeClass != null) {
				// set positive
				positiveNode = processNode(positiveClass);				
				candidates.add(positiveNode);
				processed.add(positiveNode);
				// set negative
				negativeNode = processNode(negativeClass);				
				candidates.add(negativeNode);
				processed.add(negativeNode);
			}			
		} else {
			candidates.addAll(nodeInstanceMap.keySet());
			processed.addAll(nodeInstanceMap.keySet());
		}
		// loop
		Out.p("\nEntering the main loop");
		int initialNumber = nodeInstanceMap.size();
		int iters = 0;		
		loop:			
		while (!candidates.isEmpty()) {
			ALCNode current = (ALCNode) candidates.poll();			
			// generate all extensions of labelSize+1
			Set<ALCNode> extensions = operator.refine(current);			
			// beam
			List<ALCNode> beam = new LinkedList<>();			
			// evaluate extensions			
			for (ALCNode extension : extensions) {
				OWLClassExpression concept = extension.getConcept();
				if (extension.depth() <= maxRoleDepth
						&& extension.length() <= maxConceptLength
						&& !processed.contains(extension)) {					
					// if prediction
					if (positiveClass != null && negativeClass != null
							&& (concept.containsEntityInSignature(positiveClass)
									|| concept.containsEntityInSignature(negativeClass))) {
						continue;
					}
					double t1 = System.nanoTime();
					Set<OWLNamedIndividual> instances = null;										
					try {
						if (reasoner.isSatisfiable(concept)) {
							instances = reasoner.getInstances(concept, false).getFlattened();
						}
					} catch (Exception e) {						
						Out.p(e + DLMinerOutputI.CONCEPT_BUILDING_ERROR);
					}
					extension.coverage = (instances == null) ? 0 : instances.size();
					if (instances == null) {
						continue;
					}
					updateCoverage(extension, instances);
					if (extension.coverage >= minSupport) {
						beam.add(extension);
						nodeInstanceMap.put(extension, instances);
						// record time
						double t2 = System.nanoTime();
						double time = (t2 - t1)/1e9;
						expressionTimeMap.put(concept, time);
						// break the loop if the maximal number of concepts is reached
						if (nodeInstanceMap.size() - initialNumber >= maxConceptNumber) {
							break loop;
						}
					}					
				}				
			}			
			processed.addAll(extensions);			
			// find beam concepts and add them to candidates
			Collections.sort(beam, new NodeCoverageComparator(SortingOrder.DESC));
			if (beam.size() <= beamSize) {
				candidates.addAll(beam);
			} else {
				candidates.addAll(beam.subList(0, beamSize));
			}
			// debug		
			Out.p("iterations=" + (++iters) 
					+ " concepts=" + nodeInstanceMap.size()
					+ " candidates=" + candidates.size()
					+ " extensions=" + extensions.size()
					+ " current=" + current);			
		}
		Out.p("\nDL-Apriori has terminated");		
	}*/
	
	
	
	
	private void aprioriALCOptimised() {
		PriorityQueue<ALCNode> candidates = new PriorityQueue<>(100, 
				new NodeLengthComparator(SortingOrder.ASC));		
		Set<ALCNode> processed = new HashSet<>();		
		if (nodeClusterMap.isEmpty()) {
			// the first run
			ALCNode rootNode = processNode(factory.getOWLThing());
			candidates.add(rootNode);
			processed.add(rootNode);
			// if prediction
			if (positiveClass != null && negativeClass != null) {
				// set positive
				positiveNode = processNode(positiveClass);				
				candidates.add(positiveNode);
				processed.add(positiveNode);
				// set negative
				negativeNode = processNode(negativeClass);				
				candidates.add(negativeNode);
				processed.add(negativeNode);
			}			
		} else {
			// not the first run
			candidates.addAll(nodeClusterMap.keySet());
			processed.addAll(nodeClusterMap.keySet());
		}
		// loop
		Out.p("\nEntering the main loop");
		int initialNumber = nodeClusterMap.size();
		int iters = 0;
		loop:
		while (!candidates.isEmpty()) {
			ALCNode current = (ALCNode) candidates.poll();			
			// generate all non-redundant extensions of labelSize+1
			Set<ALCNode> refinements = operator.refine(current);
			// first check atomic nodes
			if (current.isOWLThing()) {
				refinements.addAll(operator.getAtomicNodes());				
			}
			List<ALCNode> extensions = new ArrayList<>(refinements);
			Collections.sort(extensions, new NodeLengthComparator(SortingOrder.ASC));			
			// beam
			List<ALCNode> beam = new LinkedList<>();
			// get instances of a current candidate
			List<Expansion> currentInstances = nodeClusterMap.get(current);			
			// evaluate extensions			
			for (ALCNode extension : extensions) {
				OWLClassExpression concept = extension.getConcept();				
				if (extension.depth() <= maxRoleDepth
						&& extension.length() <= maxConceptLength
						&& !processed.contains(extension)) {					
					// if prediction
					if (positiveClass != null && negativeClass != null) {
						if (concept.containsEntityInSignature(positiveClass)
								|| concept.containsEntityInSignature(negativeClass)) {
							continue;
						}
					}
					double t1 = System.nanoTime();					
					List<Expansion> instances = InstanceChecker.getInstances(extension, currentInstances);					
					extension.coverage = instanceChecker.countAllInstances(instances);
//					updateCoverage(extension, instances);
					if (extension.coverage >= minSupport) {
						beam.add(extension);
						nodeClusterMap.put(extension, instances);						
						// record time
						double t2 = System.nanoTime();
						double time = (t2 - t1)/1e9;						
						expressionTimeMap.put(concept, time);
						// break the loop if the maximal number of concepts is reached
						if (nodeClusterMap.size() - initialNumber >= maxConceptNumber) {
							break loop;
						}
					}
				}				
			}			
			processed.addAll(extensions);			
			// find beam concepts and add them to candidates
//			Collections.sort(beam, new NodeCoverageComparator(SortingOrder.DESC));
			if (beam.size() <= beamSize) {
				candidates.addAll(beam);
			} else {
				candidates.addAll(beam.subList(0, beamSize));
			}
			// debug		
			Out.p("iterations=" + (++iters) 
					+ " concepts=" + nodeClusterMap.size()
					+ " candidates=" + candidates.size()
					+ " extensions=" + extensions.size()
					+ " current=" + current);			
		}		
		Out.p("\nDL-Apriori has terminated");		
	}
	
	
	
	private void updateCoverage(ALCNode extension, List<Expansion> instances) {
		if (positiveNode == null || negativeNode == null) {
			return;
		}
		List<Expansion> posInstances = nodeClusterMap.get(positiveNode);
		int posIntersect = instanceChecker.countIntersection(instances, posInstances);
		List<Expansion> negInstances = nodeClusterMap.get(negativeNode);
		int negIntersect = instanceChecker.countIntersection(instances, negInstances);
		extension.coverage = getDescriptionQuality(posIntersect, negIntersect,
				posInstances.size(), negInstances.size());
	}
	
	
	

	private void updateCoverage(ALCNode extension, Set<OWLNamedIndividual> instances) {
		if (positiveNode == null || negativeNode == null) {
			return;
		}		
		Set<OWLNamedIndividual> posInstances = nodeInstanceMap.get(positiveNode);
		int posIntersect = (int) HypothesisEvaluator.countIntersection(instances, posInstances);
		Set<OWLNamedIndividual> negInstances = nodeInstanceMap.get(negativeNode);
		int negIntersect = (int) HypothesisEvaluator.countIntersection(instances, negInstances);
		extension.coverage = getDescriptionQuality(posIntersect, negIntersect,
				posInstances.size(), negInstances.size());
	}
	
	
	
	private int getDescriptionQuality(int posIntersect, int negIntersect, 
			int pos, int neg) {
		if (posIntersect == 0 && negIntersect == 0) {
			return 0;
		}
		if (posIntersect >= negIntersect) {			
			return posIntersect;
		}		
		return negIntersect;		
	}

	



	private ALCNode processNode(OWLClass cl) {
		Set<OWLClassExpression> conjs = new HashSet<>(5);
		if (!cl.isOWLThing()) {
			conjs.add(cl);
		}
		Set<OWLClassExpression> disjs = new HashSet<>(5);
		ALCNode node = new ALCNode(conjs, disjs);
		double t1 = System.nanoTime();
//		if (reasonerMode.equals(ReasonerMode.NORM)) {
//			Set<OWLNamedIndividual> instances = null;
//			if (cl.isOWLThing()) {
//				instances = handler.getIndividuals();
//			} else {
//				try {
//					if (reasoner.isSatisfiable(cl)) {
//						instances = reasoner.getInstances(cl, false).getFlattened();
//					}
//				} catch (Exception e) {						
//					Out.p(e + DLMinerOutputI.CONCEPT_BUILDING_ERROR);
//				}
//			}
//			if (instances == null) {
//				instances = new HashSet<>();
//			}
//			node.coverage = instances.size();
//			nodeInstanceMap.put(node, instances);
//		} else {
		List<Expansion> instances = null;
		if (cl.isOWLThing()) {
			instances = new ArrayList<>(instanceChecker.getClusterCenters());			
		} else {
			instances = instanceChecker.getInstances(node);			
		}
		node.coverage = instanceChecker.countAllInstances(instances);
		nodeClusterMap.put(node, instances);
//		}
		double t2 = System.nanoTime();
		double time = (t2 - t1)/1e9;		
		expressionTimeMap.put(node.getConcept(), time);
		return node;
	}
	
	
		
	
	private void convertToExpressionInstanceMapInd() {
		for (ALCNode expr : nodeInstanceMap.keySet()) {
			OWLClassExpression conc = expr.getConcept();
			if (expressionInstanceMap.containsKey(conc)) {
				continue;
			}
			Set<OWLNamedIndividual> inds = nodeInstanceMap.get(expr);
			if (inds != null) {				
				expressionInstanceMap.put(conc, inds);
			}
		}
	}


	private void convertToExpressionInstanceMapExp() {
		// find all instances
		Out.p("\nGathering all instances");
		instanceChecker.addInstancesForClusters(nodeClusterMap, nodeExpansionMap);
		for (ALCNode expr : nodeExpansionMap.keySet()) {
			OWLClassExpression conc = expr.getConcept();
			if (expressionInstanceMap.containsKey(conc)) {
				continue;
			}
			List<Expansion> expansions = nodeExpansionMap.get(expr);
			if (expansions != null) {
				Set<OWLNamedIndividual> inds = new HashSet<>();
				for (Expansion expansion : expansions) {
					inds.add(expansion.individual);
				}
				expressionInstanceMap.put(conc, inds);
			}
		}		
	}


	public void generateAndMapDataConcepts() {
		List<OWLClass> cls = languageClassMap.get(Language.DATA_C);
		if (cls == null) {
			cls = new ArrayList<>();
			languageClassMap.put(Language.DATA_C, cls);			
		}
		for (OWLClassExpression expr : expressionInstanceMap.keySet()) {
			if (!expressionClassMap.containsKey(expr)) {
				OWLClass cl = null;
				if (!expr.isAnonymous()) {
					cl = expr.asOWLClass();
				} else {
					cl = handler.generateClass();
				}
				cls.add(cl);
				expressionClassMap.put(expr, cl);
				classExpressionMap.put(cl, expr);
				classInstanceMap.put(cl, expressionInstanceMap.get(expr));
			}
		}		
	}
	
	
		
	private Map<OWLNamedIndividual, ELNode> buildABoxGraphFromAssertions() {		
		Map<OWLNamedIndividual, Set<OWLClassAssertionAxiom>> indCAssMap = 
				handler.createIndClassAssertionMap();
		Map<OWLNamedIndividual, Set<OWLObjectPropertyAssertionAxiom>> indRAssMap = 
				handler.createIndPropertyAssertionMap();		
		// create an ABox graph
		Map<OWLNamedIndividual, ELNode> aboxMap = 
				new HashMap<>();
		// create nodes
		Set<OWLNamedIndividual> inds = handler.getIndividuals();
		for (OWLNamedIndividual ind : inds) {
			Set<OWLClassAssertionAxiom> cfacts = indCAssMap.get(ind);
			Set<OWLClassExpression> label = null;
			if (cfacts != null) {
				label = handler.getExpressionsFromAssertions(cfacts);
			} else {
				label = new HashSet<>(1);
			}
			ELNode node = new ELNode(label);
			aboxMap.put(ind, node);
		}
		// build map of universals
		Map<OWLClassExpression, Set<OWLObjectAllValuesFrom>> classUniversalMap = new HashMap<>();
		for (OWLAxiom ax : handler.getTBoxAxioms()) {
			if (ax instanceof OWLSubClassOfAxiom) {
				OWLSubClassOfAxiom classAxiom = (OWLSubClassOfAxiom) ax;
				OWLClassExpression subClass = classAxiom.getSubClass();
				OWLClassExpression superClass = classAxiom.getSuperClass();
				if (superClass instanceof OWLObjectAllValuesFrom) {
					OWLObjectAllValuesFrom univ = (OWLObjectAllValuesFrom) superClass;
					// add only atomic fillers
					if (univ.getFiller().isAnonymous()) {
						continue;
					}
					Set<OWLObjectAllValuesFrom> universals = classUniversalMap.get(subClass);
					if (universals == null) {
						universals = new HashSet<>();
						classUniversalMap.put(subClass, universals);
					}
					universals.add(univ);
				}
			}
		}
		// create relations
		Map<OWLObjectProperty, Set<OWLClass>> propRangeMap = operator.getPropRangeMap();
		Map<OWLClass, Set<OWLClass>> eqClMap = operator.getEquivClassMap();
		Map<OWLClass, Set<OWLClass>> supClMap = operator.getSuperClassMap();
		for (OWLNamedIndividual ind : indRAssMap.keySet()) {
			Set<OWLClassAssertionAxiom> cfacts = indCAssMap.get(ind);
			Set<OWLClassExpression> indLabel = handler.getExpressionsFromAssertions(cfacts);
			Set<OWLObjectPropertyAssertionAxiom> rfacts = indRAssMap.get(ind);			
			ELNode subj = aboxMap.get(ind);
			for (OWLObjectPropertyAssertionAxiom rfact : rfacts) {
				ELNode obj = aboxMap.get(rfact.getObject());
				SomeEdge edge = new SomeEdge(subj, rfact.getProperty(), obj);
				List<CEdge> sEdges = subj.getOutEdges();				
				subj.addOutEdge(edge);								
				// add property ranges as universals
				OWLObjectPropertyExpression propExpr = rfact.getProperty();
				if (propExpr.isAnonymous()) {
					continue;
				}
				OWLObjectProperty prop = propExpr.asOWLObjectProperty();
				if (propRangeMap.containsKey(prop)) {
					Set<OWLClass> rangeCls = propRangeMap.get(prop);
					if (rangeCls.size() <= 1) {
						continue;
					}
					Set<OWLClassExpression> rangeLabel = new HashSet<>();
					rangeLabel.addAll(rangeCls);
					rangeLabel.remove(factory.getOWLThing());
					if (!rangeLabel.isEmpty()) {
						ELNode range = new ELNode(rangeLabel);
						OnlyEdge univEdge = new OnlyEdge(subj, prop, range);
						if (sEdges != null) {
							subj.addOutEdge(univEdge);	
						}
					}
				}
				// add additional universals
				for (OWLClassExpression lab : indLabel) {
					Set<OWLObjectAllValuesFrom> universals = classUniversalMap.get(lab);
					if (universals == null) {
						continue;
					}
					for (OWLObjectAllValuesFrom univ : universals) {
						if (prop.equals(univ.getProperty())) {
							OWLClass rangeClass = univ.getFiller().asOWLClass();
							Set<OWLClassExpression> rangeLabel = new HashSet<>();
							rangeLabel.addAll(eqClMap.get(rangeClass));
							rangeLabel.addAll(supClMap.get(rangeClass));
							rangeLabel.remove(factory.getOWLThing());
							if (rangeLabel.isEmpty()) {
								continue;
							}
							ELNode range = new ELNode(rangeLabel);
							OnlyEdge univEdge = new OnlyEdge(subj, prop, range);
							if (sEdges != null) {
								subj.addOutEdge(univEdge);	
							}
						}
					}
				}
			}
		}
		return aboxMap;
	}
	
	
	private Map<OWLNamedIndividual, ELNode> buildABoxGraphFromReasoner() {
		Map<OWLNamedIndividual, Set<OWLClassExpression>> indCExprMap = 
				handler.createIndClassExprMapInferred(classes, reasoner);
		Map<OWLNamedIndividual, Set<OWLObjectPropertyAssertionAxiom>> indRAssMap = 
				handler.createIndPropertyAssertionMapInferred(reasoner);		
		// create an ABox graph
		Map<OWLNamedIndividual, ELNode> aboxMap = 
				new HashMap<>();
		// create nodes
		Set<OWLNamedIndividual> inds = handler.getIndividuals();
		for (OWLNamedIndividual ind : inds) {
			Set<OWLClassExpression> label = indCExprMap.get(ind);
			if (label == null) {
				label = new HashSet<>();
			}
			ELNode node = new ELNode(label);
			aboxMap.put(ind, node);
		}
		// create relations
		for (OWLNamedIndividual ind : indRAssMap.keySet()) {
			Set<OWLObjectPropertyAssertionAxiom> rfacts = indRAssMap.get(ind);
			ELNode subj = aboxMap.get(ind);
			for (OWLObjectPropertyAssertionAxiom rfact : rfacts) {
				ELNode obj = aboxMap.get(rfact.getObject());
				SomeEdge edge = new SomeEdge(subj, rfact.getProperty(), obj);
				subj.addOutEdge(edge);				
			}
		}
		return aboxMap;
	}
	
	
		
	
	


	/**
	 * @return mapping of individuals to their concepts
	 */
	public Map<OWLNamedIndividual, Set<OWLClassExpression>> 
		createIndClassExprMapInferred() {
		Map<OWLNamedIndividual, Set<OWLClassExpression>> map = new HashMap<>();		
		for (OWLClassExpression expr : classes) {			
			Set<OWLNamedIndividual> insts = reasoner.getInstances(expr, false).getFlattened();
			for (OWLNamedIndividual inst : insts) {
				Set<OWLClassExpression> exprs = map.get(inst);
				if (exprs == null) {
					exprs = new HashSet<>();
					map.put(inst, exprs);
				}
				exprs.add(expr);
			}			
		}	
		return map;
	}
	
	
	
	private void debugBuildingConcepts(List<ELNode> expansions) {
		Out.p("\n" + expansions.size() + " trees:");
		List<ELNode> duplicates = new LinkedList<>();
		Set<ELNode> unique = new HashSet<>();
		for (ELNode n : expansions) {
			ELNode node = n.normalise();			
			if (!unique.contains(node)) {
				unique.add(node);
			} else {
				duplicates.add(node);
			}
			Out.p(node + ", hash=" + node.hashCode());
		}				
		Out.p("\n" + duplicates.size() + " duplicates:");		
		for (ELNode n : duplicates) {			
			Out.p(n + ", hash=" + n.hashCode());			
		}		
		Out.p("\nEqual trees:");
		for (ELNode n1 : unique) {
			for (ELNode n2 : unique) {				
				if (n1.equals(n2)) {
					ELNode n3 = n1.multiply(n2);
					Out.p("n1 = " + n1 + 
							"\t norm(n1) = " + n1.normalise() + 
							"\t n2 = " + n2 +  
							"\t norm(n2) = " + n2.normalise() + 
							"\t n3 := n1xn2 = " + n3 + 
							"\t norm(n3) = " + n3.normalise() +
							"\t equal(n3,n1) = " + n3.equals(n1) + 
							"\t equal(n3,n2) = " + n3.equals(n2));						
				}
			}
		}
		NumberFormat formatter = new DecimalFormat("#0.00");
		Out.p("\nSimilarity matrix:");
		for (ELNode n1 : unique) {
			for (ELNode n2 : unique) {			
				Out.s(formatter.format(n1.similarity(n2)) + "\t");
			}
			Out.p("");
		}
	}


	private List<Expansion> 
		buildIndividualExpansions(Map<OWLNamedIndividual, ELNode> aboxMap) {
		List<Expansion> expansions = new ArrayList<>(aboxMap.size());
		for (OWLNamedIndividual ind : aboxMap.keySet()) {			
			// ind's node
			ELNode node = aboxMap.get(ind);			
			// root
			Expansion root = new Expansion(node.labels);
			root.individual = ind;
			root.pointer = node;
			root.depth = 0;
			LinkedList<Expansion> remainNodes = new LinkedList<>();
			remainNodes.add(root);
			// breadth-first traversal up to roleDepth
			while (!remainNodes.isEmpty()) {
				Expansion current = remainNodes.pollFirst();
				LinkedList<CEdge> edges = current.pointer.getOutEdges();
				if (edges != null) {					
					for (CEdge edge : edges) {
						ELNode obj = (ELNode) edge.object;
						Expansion child = new Expansion(obj.labels);						
						child.depth = current.depth + 1;
						if (child.depth <= maxRoleDepth) {
							child.pointer = obj;
							CEdge newEdge = null;
							if (edge instanceof SomeEdge) {
								newEdge = new SomeEdge(current, edge.label, child);
							} else {
								newEdge = new OnlyEdge(current, edge.label, child);
							}
							current.addOutEdge(newEdge);
							remainNodes.add(child);
						}
					}
				}
			}			
			// add
			expansions.add(root);
//			Out.p(root);
		}
		Out.p(expansions.size() + " expansions are built");
		return expansions;
	}
	
	
	


	private Set<OWLClassExpression> buildConjunctions(Set<OWLClassExpression> cls) {				
		Set<OWLClassExpression> conjs = new HashSet<>();		
		if (cls.size() > 1) {
			conjs.add(factory.getOWLObjectIntersectionOf(cls));
		} else {
			conjs.addAll(cls);
		}
		return conjs;		
	}
	
	
	private Set<OWLClassExpression> buildExistentials(Set<OWLClassExpression> cls, 
			OWLObjectPropertyExpression prop) {
		Set<OWLClassExpression> exists = new HashSet<>();
		for (OWLClassExpression expr : cls) {
			exists.add(factory.getOWLObjectSomeValuesFrom(prop, expr));
		}		
		return exists;
	}
		
	

	private List<OWLClassExpression> buildSubConcepts() {		
		List<OWLClassExpression> subcons = new ArrayList<>(classes.size());
		Set<OWLAxiom> axioms = handler.copyAxioms();
		for (OWLAxiom ax : axioms) {			
			subcons.addAll(ax.getNestedClassExpressions());
		}
		subcons.removeAll(classes);		
		return subcons;
	}
	
	
	private List<OWLClassExpression> buildSubConceptsForSignature() {		
		List<OWLClassExpression> subcons = new ArrayList<>(classes.size());
		Set<OWLAxiom> axioms = handler.copyAxioms();
		for (OWLAxiom ax : axioms) {	
			Set<OWLClassExpression> nestedCons = ax.getNestedClassExpressions();
			for (OWLClassExpression nestedCon : nestedCons) {
				if (signature.containsAll(nestedCon.getSignature())) {
					subcons.add(nestedCon);
				}
			}
		}
		subcons.removeAll(classes);		
		return subcons;
	}
	
	
	private List<OWLClassExpression> generateNotAs(List<? extends OWLClassExpression> As) {
		// not A
		List<OWLClassExpression> notAs = new ArrayList<>(As.size());
		for (OWLClassExpression A : As) {
			if (!A.isOWLThing() && !A.isOWLNothing()) {
				// generate an expression
				OWLClassExpression notA = factory.getOWLObjectComplementOf(A);			
				notAs.add(notA);
			}
		}
		return notAs;
	}
	
	
	private List<OWLClassExpression> generateAandBs(List<? extends OWLClassExpression> As, List<? extends OWLClassExpression> Bs) {
		// A and B		
		Set<OWLClassExpression> set = new HashSet<>();
		for (OWLClassExpression A : As) {
			for (OWLClassExpression B : Bs) {
				if (!A.equals(B) && !A.isOWLThing() && !B.isOWLThing()) {					
					// generate an expression
					OWLClassExpression AandB = factory.getOWLObjectIntersectionOf(A, B);			
					set.add(AandB);
				}
			}
		}
		List<OWLClassExpression> AandBs = new ArrayList<>(set);
		return AandBs;
	}
	
	
	private List<OWLClassExpression> generateAorBs(List<? extends OWLClassExpression> As,
			List<? extends OWLClassExpression> Bs) {
		// A or B
		Set<OWLClassExpression> set = new HashSet<>();
		for (OWLClassExpression A : As) {
			for (OWLClassExpression B : Bs) {
				if (!A.equals(B) && !A.isOWLThing() && !B.isOWLThing()) {					
					// generate an expression
					OWLClassExpression AorB = factory.getOWLObjectUnionOf(A, B);			
					set.add(AorB);
				}
			}
		}
		List<OWLClassExpression> AorBs = new ArrayList<>(set);
		return AorBs;
	}
	
	
	private List<OWLClassExpression> generateRonlyAs(
			List<? extends OWLClassExpression> As) {
		// R some A
		List<OWLClassExpression> RonlyAs = new ArrayList<>(As.size()*properties.size());
		for (OWLClassExpression A : As) {			
			for (OWLObjectProperty R : properties) {
				// generate an expression
				OWLClassExpression RonlyA = factory.getOWLObjectAllValuesFrom(R, A);			
				RonlyAs.add(RonlyA);
			}			
		}
		return RonlyAs;
	}
	
	
	
	private List<OWLClassExpression> generateRsomeAs(List<? extends OWLClassExpression> As) {
		// R some A
		List<OWLClassExpression> RsomeAs = new ArrayList<>(As.size()*properties.size());
		for (OWLClassExpression A : As) {			
			for (OWLObjectProperty R : properties) {
				// generate an expression
				OWLClassExpression RsomeA = factory.getOWLObjectSomeValuesFrom(R, A);			
				RsomeAs.add(RsomeA);
			}			
		}
		return RsomeAs;
	}
	
	
	private List<OWLClassExpression> generateRsomeAs(
			List<? extends OWLObjectPropertyExpression> Rs, List<? extends OWLClassExpression> As) {
		// R some A
		List<OWLClassExpression> RsomeAs = new ArrayList<>(As.size()*Rs.size());
		for (OWLClassExpression A : As) {			
			for (OWLObjectPropertyExpression R : Rs) {
				// generate an expression
				OWLClassExpression RsomeA = factory.getOWLObjectSomeValuesFrom(R, A);			
				RsomeAs.add(RsomeA);
			}			
		}
		return RsomeAs;
	}
	
	
	private List<OWLObjectPropertyExpression> generateInverseRs(List<? extends OWLObjectPropertyExpression> Rs) {
		// inverse of R		
		List<OWLObjectPropertyExpression> invRs = new ArrayList<>(Rs.size());
		for (OWLObjectPropertyExpression R : Rs) {
			if (!R.isOWLTopObjectProperty() && !R.isOWLBottomObjectProperty()) {
				// generate an expression
				OWLObjectPropertyExpression invR = factory.getOWLObjectInverseOf(R);			
				invRs.add(invR);
			}
		}
		return invRs;
	}
	
	
	private List<OWLObjectPropertyExpression> generateInverseRs() {
		return generateInverseRs(properties);		
	}
	
	
	private List<OWLObjectPropertyExpression> generateRchainSs(
			List<? extends OWLObjectPropertyExpression> Rs,
			List<? extends OWLObjectPropertyExpression> Ss) {		
		// R chain S		
		Set<OWLObjectPropertyExpression> set = new HashSet<>();
		for (OWLObjectPropertyExpression R : Rs) {
			for (OWLObjectPropertyExpression S : Ss) {								
				// generate an expression
				OWLObjectPropertyChain RchainS = new OWLObjectPropertyChain(R, S);					
				set.add(RchainS);				
			}
		}
		List<OWLObjectPropertyExpression> RchainSs = new ArrayList<>(set);
		return RchainSs;		
	}
	
	
	
/*	private List<OWLClassExpression> generateCDs(Map<OWLClass, Set<OWLNamedIndividual>> posMap) {
		
		// get all individuals
		Set<OWLNamedIndividual> inds = new HashSet<OWLNamedIndividual>();		
		for (OWLClass cl : posMap.keySet()) {
			inds.addAll(posMap.get(cl));
		}
		
		// get points
		List<OWLNamedIndividual> indList = new ArrayList<OWLNamedIndividual>(inds);
		List<OWLClass> clList = new ArrayList<OWLClass>(posMap.keySet());
		double[][] points = new double[indList.size()][clList.size()];
		for (int j=0; j<points[0].length; j++) {
			Set<OWLNamedIndividual> insts = posMap.get(clList.get(j));
			for (int i=0; i<points.length; i++) {
				if (insts.contains(indList.get(i))) {
					points[i][j] = 1;
				}
			}
		}
		
		// cluster individuals
		Out.p("\nClustering individuals...");
		int stop = 1;
		int runs = 10;
		int min = 1;
		int max = clList.size();
		int step = 1;
		KMeans kmeans = new KMeans(points, stop, KMeans.RANDOM_SPREAD, KMeans.DIST_EUCLID);
		int[][] hier = kmeans.clusterHierarchy(min, step, max, runs);
		
		// get clusters (unique)
		Set<Set<Integer>> clusters = new HashSet<Set<Integer>>();
		for (int i=0; i<hier.length; i++) {
			Set<Integer> ids = new HashSet<Integer>();
			for (int j=0; j<hier[0].length; j++) {
				ids.add(hier[i][j]);
			}
			for (Integer id : ids) {
				Set<Integer> cluster = new HashSet<Integer>();
				for (int j=0; j<hier[0].length; j++) {
					if (id == hier[i][j]) {
						cluster.add(j);
					}
				}
				clusters.add(cluster);
			}
		}
		
		// learn concepts
		Out.p("\nLearning concept descriptions for " + clusters.size() + " clusters...");
//		OWLAPIOntology ks = new OWLAPIOntology(handler.getOntology());
//		OWLAPIReasoner reasoningService = new OWLAPIReasoner(ks);
		OWLAPIReasoner reasoningService = new OWLAPIReasoner(reasoner);
		if (!reasoningService.isInitialized()) {
			try {
				reasoningService.init();
			} catch (ComponentInitException e) {			
				e.printStackTrace();
			}
		}
		int count = 0;
		for (Set<Integer> cluster : clusters) {
			SortedSet<OWLIndividual> positiveExamples = getClusterIndividuals(cluster, indList);
			PosOnlyLP learningProblem = new PosOnlyLP(reasoningService, positiveExamples);
			CELOE celoe = new CELOE(learningProblem, reasoningService);
			try {
				celoe.init();
				celoe.start();
			} catch (ComponentInitException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}			
			OWLClassExpression best = celoe.getCurrentlyBestDescription();			
			expressionInstanceMap.put(best, getClusterNamedIndividuals(cluster, indList));
			count++;
			Out.p(count + " descriptions learned");
		}
		
		return new LinkedList<OWLClassExpression>(expressionInstanceMap.keySet());
	}
*/	
	
	private SortedSet<OWLIndividual> getClusterIndividuals(Set<Integer> cluster, List<OWLNamedIndividual> indList) {
		SortedSet<OWLIndividual> inds = new TreeSet<>();
		for (Integer id : cluster) {
			inds.add(indList.get(id));
		}
		return inds;
	}
	
	private Set<OWLNamedIndividual> getClusterNamedIndividuals(Set<Integer> cluster, List<OWLNamedIndividual> indList) {
		Set<OWLNamedIndividual> inds = new HashSet<>();
		for (Integer id : cluster) {
			inds.add(indList.get(id));
		}
		return inds;
	}


	public OWLAxiom getClassAssertion(OWLClassExpression cl, OWLIndividual ind) {
		return factory.getOWLClassAssertionAxiom(cl, ind);
	}
	
	private OWLAxiom getRoleAssertion(OWLNamedIndividual subj,
			OWLObjectPropertyExpression prop, OWLNamedIndividual obj) {
		return factory.getOWLObjectPropertyAssertionAxiom(prop, subj, obj);
	}
		
	
	public void addDefinitions() {		
		handler.addAxioms(classExpressionDefinitionMap.values());
		handler.addAxioms(roleExpressionDefinitionMap.values());
	}
	
		
	public void addDefinitions(Collection<Hypothesis> hypotheses, 
			Map<OWLClass, Set<OWLNamedIndividual>> conceptInstMap,
			Map<OWLObjectProperty, Set<List<OWLNamedIndividual>>> roleInstMap) {
		Set<OWLClassExpression> clExprs = new HashSet<>();
		Set<OWLObjectPropertyExpression> propExprs = new HashSet<>();
		for (Hypothesis h : hypotheses) {
			for (OWLAxiom ax : h.axioms) {
				if (ax instanceof OWLSubClassOfAxiom) {
					OWLSubClassOfAxiom axiom = (OWLSubClassOfAxiom) ax;
					clExprs.add(axiom.getSubClass());
					clExprs.add(axiom.getSuperClass());
				}
				if (ax instanceof OWLSubObjectPropertyOfAxiom) {
					OWLSubObjectPropertyOfAxiom axiom = (OWLSubObjectPropertyOfAxiom) ax;
					propExprs.add(axiom.getSubProperty());
					propExprs.add(axiom.getSuperProperty());
				}
			}
		}
		// add definitions
		for (OWLClassExpression expr : clExprs) {
			OWLAxiom def = classExpressionDefinitionMap.get(expr);
			if (def != null) {
				handler.addAxiom(def);			
			}
		}
		for (OWLObjectPropertyExpression expr : propExprs) {
			OWLAxiom def = roleExpressionDefinitionMap.get(expr);
			if (def != null) {
				handler.addAxiom(def);			
			}
		}
		// clean mappings		
		Set<OWLClass> cls = new HashSet<>(conceptInstMap.keySet());
		for (OWLClass cl : cls) {
			OWLClassExpression expr = classExpressionMap.get(cl);
			if (expr != null && !clExprs.contains(expr)) {
				conceptInstMap.remove(cl);
			}
		}
		Set<OWLObjectProperty> props = new HashSet<>(roleInstMap.keySet());
		for (OWLObjectProperty prop : props) {
			OWLObjectPropertyExpression expr = roleExpressionMap.get(prop);
			if (expr != null && !propExprs.contains(expr)) {
				conceptInstMap.remove(prop);
			}
		}
	}

	
	public void removeDefinitions() {
		handler.removeAxioms(classExpressionDefinitionMap.values());
		handler.removeAxioms(roleExpressionDefinitionMap.values());
	}
	
	public void addDefinitions(Language lang, OntologyHandler handler) {
		// classes
		if (!lang.equals(Language.A)) {
			List<OWLClass> cls = languageClassMap.get(lang);
			if (cls != null) {
				for (OWLClass cl : cls) {
					OWLClassExpression expr = classExpressionMap.get(cl);
					OWLAxiom ax = classExpressionDefinitionMap.get(expr);
					if (ax != null) {
						handler.addAxiom(ax);
					}
				}
			}
		}
		// roles
		if (!lang.equals(Language.R)) {
			List<OWLObjectProperty> props = languageRoleMap.get(lang);
			if (props != null) {
				for (OWLObjectProperty prop : props) {
					OWLObjectPropertyExpression expr = roleExpressionMap.get(prop);
					OWLAxiom ax = roleExpressionDefinitionMap.get(expr);
					if (ax != null) {
						handler.addAxiom(ax);
					}
				}
			}
		}
	}
	
	
		
	public Map<Language, List<OWLClass>> getLanguageClassMap() {
		return languageClassMap;
	}
	
	public Map<Language, List<OWLObjectProperty>> getLanguageRoleMap() {
		return languageRoleMap;
	}
	

	public Map<OWLClass, OWLClassExpression> getClassExpressionMap() {
		return classExpressionMap;
	}
	
	public Map<OWLClassExpression, OWLClass> getExpressionClassMap() {
		return expressionClassMap;
	}


	public Map<OWLObjectProperty, OWLObjectPropertyExpression> getRoleExpressionMap() {
		return roleExpressionMap;
	}

	public OntologyHandler getHandler() {
		return handler;
	}
	

	public OWLClassExpression getExpressionByClass(OWLClass cl) {
		return classExpressionMap.get(cl);
	}
	
	public OWLObjectPropertyExpression getExpressionByRole(OWLObjectProperty prop) {
		return roleExpressionMap.get(prop);
	}
	
	public OWLAxiom getDefinitionByExpression(OWLClassExpression expr) {
		return classExpressionDefinitionMap.get(expr);
	}
	
	public OWLAxiom getDefinitionByExpression(OWLObjectPropertyExpression expr) {
		return roleExpressionDefinitionMap.get(expr);
	}
	
	public Set<OWLNamedIndividual> getInstancesByExpression(OWLClassExpression cl) {
		return expressionInstanceMap.get(cl);
	}


	public void update() {
		updateClasses();
		updateRoles();
	}
	
	private void updateClasses() {
		// handler is updated already
		Set<OWLClass> sigCls = handler.getClassesInSignature();
		classes.retainAll(sigCls);
		retainClasses(sigCls);
	}
	
	
	private void retainClasses(Set<OWLClass> retains) {		
		Set<Language> langs = new HashSet<>(languageClassMap.keySet());
		for (Language lang : langs) {
			retainClasses(retains, lang);
		}		
	}
	
	
	private void retainClasses(Set<OWLClass> retains, Language lang) {
		List<OWLClass> cls = languageClassMap.get(lang);		
		if (cls != null) {
			Set<OWLClass> removals = new HashSet<>(cls);
			cls.retainAll(retains);
			// remove languages
			if (cls.isEmpty()) {
				languageClassMap.remove(lang);
			}
			// remove expressions and definitions 
			// if not in handler's signature			
			for (OWLClass cl : removals) {
				if (!retains.contains(cl)) {
					OWLClassExpression rem = classExpressionMap.remove(cl);
					expressionClassMap.remove(rem);
					OWLAxiom del = classExpressionDefinitionMap.remove(rem);			
				}
			}
		}		
	}

	
	
	private void updateRoles() {
		// handler is updated already
		Set<OWLObjectProperty> sigRoles = handler.getObjectPropertiesInSignature();
		properties.retainAll(sigRoles);
		retainRoles(sigRoles);		
	}
	
	
	private void retainRoles(Set<OWLObjectProperty> retains) {		
		Set<Language> langs = new HashSet<>(languageRoleMap.keySet());
		for (Language lang : langs) {
			retainRoles(retains, lang);
		}		
	}
	
	
	private void retainRoles(Set<OWLObjectProperty> retains, Language lang) {
		List<OWLObjectProperty> roles = languageRoleMap.get(lang);
		if (roles != null) {
			Set<OWLObjectProperty> removals = new HashSet<>(roles);
			roles.retainAll(retains);
			// remove languages
			if (roles.isEmpty()) {
				languageClassMap.remove(lang);
			}
			// remove expressions and definitions 
			// if not in handler's signature			
			for (OWLObjectProperty prop : removals) {
				if (!retains.contains(prop)) {
					OWLObjectPropertyExpression rem = roleExpressionMap.remove(prop);
					expressionRoleMap.remove(rem);
					roleExpressionDefinitionMap.remove(rem);
				}
			}
		}

	}


	public IRI getIRI() {
		return handler.getIRI();
	}

	public Collection<OWLAxiom> getClassDefinitions() {
		return classExpressionDefinitionMap.values();
	}
	
	public Collection<OWLAxiom> getRoleDefinitions() {
		return roleExpressionDefinitionMap.values();
	}
	
	public Collection<OWLAxiom> getClassDefinitions(Collection<Hypothesis> hypotheses) {
		// collect expressions
		Set<OWLClassExpression> exprs = new HashSet<>();
		for (Hypothesis h : hypotheses) {
			for (OWLAxiom ax : h.axioms) {
				if (ax instanceof OWLSubClassOfAxiom) {
					exprs.add(OntologyHandler.getSubClass(ax));
					exprs.add(OntologyHandler.getSuperClass(ax));
				}
			}
		}		
		// retrieve definitions
		Set<OWLAxiom> defs = new HashSet<>();
		for (OWLClassExpression expr : exprs) {
			OWLAxiom def = classExpressionDefinitionMap.get(expr);
			if (def != null) {
				defs.add(def);
			}
		}		
		return defs;
	}

	public Collection<OWLAxiom> getRoleDefinitions(Collection<Hypothesis> hypotheses) {
		// collect expressions
		Set<OWLObjectPropertyExpression> exprs = new HashSet<>();
		for (Hypothesis h : hypotheses) {
			for (OWLAxiom ax : h.axioms) {
				if (ax instanceof OWLSubObjectPropertyOfAxiom) {
					exprs.add(OntologyHandler.getSubProperty(ax));
					exprs.add(OntologyHandler.getSuperProperty(ax));
				}
			}
		}
		// retrieve definitions
		Set<OWLAxiom> defs = new HashSet<>();
		for (OWLObjectPropertyExpression expr : exprs) {
			OWLAxiom def = roleExpressionDefinitionMap.get(expr);
			if (def != null) {
				defs.add(def);
			}
		}		
		return defs;
	}
		

	public OWLReasoner getReasoner() {
		return reasoner;
	}


	public void setReasoner(OWLReasoner reasoner) {
		this.reasoner = reasoner;
	}


	
	public OWLDataFactory getFactory() {
		return factory;
	}


	
		
	private void fillInstanceMapForRoles(
			Map<OWLObjectPropertyExpression, Set<List<OWLNamedIndividual>>> roleExprInstMap) {
		List<OWLObjectProperty> props = languageRoleMap.get(Language.R);
		if (props == null) {
			return;
		}		
		for (OWLObjectProperty prop : props) {
			OWLObjectPropertyExpression expr = roleExpressionMap.get(prop);			
			Set<OWLNamedIndividual> subjs = instanceChecker.getObjectPropertySubjects(expr);
			if (subjs == null) {
				continue;
			}
			Set<List<OWLNamedIndividual>> insts = new HashSet<>();
			roleExprInstMap.put(expr, insts);			
			for (OWLNamedIndividual subj : subjs) {
				Set<OWLNamedIndividual> objs = instanceChecker.getObjectPropertyValues(subj, expr);
				for (OWLNamedIndividual obj : objs) {
					List<OWLNamedIndividual> inst = new ArrayList<>(3);
					inst.add(subj);
					inst.add(obj);
					insts.add(inst);
				}
			}
		}		
	}
	
	
	private void fillInstanceMapForInverseRoles(
			Map<OWLObjectPropertyExpression, Set<List<OWLNamedIndividual>>> roleExprInstMap) {
		List<OWLObjectProperty> props = languageRoleMap.get(Language.R);
		if (props == null) {
			return;
		}
		// in the same order as R
		List<OWLObjectProperty> invProps = languageRoleMap.get(Language.INV_R);
		if (invProps == null) {
			return;
		}
		for (int i=0; i<props.size(); i++) {
			OWLObjectProperty prop = props.get(i);
			OWLObjectPropertyExpression expr = roleExpressionMap.get(prop);
			OWLObjectProperty invProp = invProps.get(i);
			OWLObjectPropertyExpression invExpr = roleExpressionMap.get(invProp);
			Set<List<OWLNamedIndividual>> insts = roleExprInstMap.get(expr);
			if (insts == null) {
				continue;
			}
			Set<List<OWLNamedIndividual>> invInsts = new HashSet<>(insts.size());
			roleExprInstMap.put(invExpr, invInsts);
			for (List<OWLNamedIndividual> inst : insts) {
				List<OWLNamedIndividual> invInst = new ArrayList<>(3);
				invInst.add(inst.get(1));
				invInst.add(inst.get(0));
				invInsts.add(invInst);
			}
		}					
	}
	
	
	private void fillInstanceMapForRoleChains(List<OWLObjectProperty> props,
			Map<OWLObjectPropertyExpression, Set<List<OWLNamedIndividual>>> roleExprInstMap) {
		if (props == null) {
			return;
		}
		for (OWLObjectProperty prop : props) {
			// role two-step chains
			OWLObjectPropertyChain chain = (OWLObjectPropertyChain) roleExpressionMap.get(prop);
			List<OWLObjectPropertyExpression> exprs = chain.getPropertyExpressions();
			Set<OWLNamedIndividual> subjs = instanceChecker.getObjectPropertySubjects(exprs.get(0));
			if (subjs == null) {
				continue;
			}
			Set<List<OWLNamedIndividual>> insts = null;
			for (OWLNamedIndividual subj : subjs) {
				Set<OWLNamedIndividual> objs = instanceChecker.getPropertyChainValues(subj, chain);
				if (objs != null) {					
					for (OWLNamedIndividual obj : objs) {
						if (insts == null) {
							insts = new HashSet<>();
							roleExprInstMap.put(chain, insts);
						}
						List<OWLNamedIndividual> inst = new ArrayList<>(3);
						inst.add(subj);
						inst.add(obj);
						insts.add(inst);
					}
				}
			}			
		}		
	}	
	


	public boolean isEmpty() {		
		return languageClassMap.isEmpty() && languageRoleMap.isEmpty();
	}


		

	public void updateClassProjection(Set<OWLAxiom> classProjection,
			Language lang) {
		List<OWLClass> cls = languageClassMap.get(lang);
		if (cls != null) {
			for (OWLClass cl : cls) {
				Set<OWLNamedIndividual> insts = reasoner.getInstances(cl, false).getFlattened();			
				for (OWLNamedIndividual inst : insts) {
					if (inst != null) {
						OWLAxiom ca = getClassAssertion(cl, inst);
						classProjection.add(ca);
					}
				}
			}
		}
	}




	public OWLClass getClassByExpression(OWLClassExpression expr) {
		return expressionClassMap.get(expr);
	}


	public OWLObjectProperty getRoleByExpression(OWLObjectPropertyExpression expr) {
		return expressionRoleMap.get(expr);
	}


	public Map<OWLClass, Set<OWLNamedIndividual>> getClassInstanceMap() {		
		return classInstanceMap;
	}


	public Map<OWLObjectProperty, Set<List<OWLNamedIndividual>>> getRoleInstanceMap() {
		return roleInstanceMap;
	}


	public Map<OWLClassExpression, Set<OWLNamedIndividual>> getClassExpressionInstanceMap() {		
		return expressionInstanceMap;
	}



	/**
	 * @return the expressionTimeMap
	 */
	public Map<OWLClassExpression, Double> getExpressionTimeMap() {
		return expressionTimeMap;
	}



	public double getTimeByExpression(OWLClassExpression expr) {
		return expressionTimeMap.get(expr);
	}



	/**
	 * @param checkDisjointness the checkDisjointness to set
	 */
	public void setCheckDisjointness(boolean checkDisjointness) {
		this.checkDisjointness = checkDisjointness;
	}



	/**
	 * @param useDisjunction the useDisjunction to set
	 */
	public void setUseDisjunction(boolean useDisjunction) {
		this.useDisjunction = useDisjunction;
	}



	/**
	 * @param useNegation the useNegation to set
	 */
	public void setUseNegation(boolean useNegation) {
		this.useNegation = useNegation;
	}



	/**
	 * @param useUniversalRestriction the useUniversalRestriction to set
	 */
	public void setUseUniversalRestriction(boolean useUniversalRestriction) {
		this.useUniversalRestriction = useUniversalRestriction;
	}



	public void retainClassDefinitions(Set<Hypothesis> hypotheses) {
		// collect expressions
		Set<OWLClassExpression> exprs = new HashSet<>();
		for (Hypothesis h : hypotheses) {
			for (OWLAxiom ax : h.axioms) {
				if (ax instanceof OWLSubClassOfAxiom) {
					exprs.add(OntologyHandler.getSubClass(ax));
					exprs.add(OntologyHandler.getSuperClass(ax));
				}
			}
		}
		// remove unnecessary concepts
		Set<OWLClassExpression> keys = new HashSet<>(expressionClassMap.keySet());
		for (OWLClassExpression key : keys) {
			if (!exprs.contains(key)) {
				classExpressionDefinitionMap.remove(key);				
			}
		}
		
	}



	
	
					
}
