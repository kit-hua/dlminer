package io.dlminer.refine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


import io.dlminer.graph.ALCNode;
import io.dlminer.graph.CEdge;
import io.dlminer.graph.CNode;
import io.dlminer.graph.DataEdge;
import io.dlminer.graph.GDataEdge;
import io.dlminer.graph.LDataEdge;
import io.dlminer.graph.NumericNode;
import io.dlminer.graph.OnlyEdge;
import io.dlminer.graph.SomeEdge;

import io.dlminer.main.DLMinerOutputI;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.dlminer.ont.LengthMetric;
import io.dlminer.print.Out;
import uk.ac.manchester.cs.owl.owlapi.OWLDataFactoryImpl;


public class ALCOperator extends RefinementOperator {


	// structures
	private Map<OWLClassExpression, Set<OWLClassExpression>> classHierarchy;
	private Map<OWLClassExpression, OWLClassExpression> negationMap;
	private Map<OWLClassExpression, Set<OWLNamedIndividual>> classInstanceMap;

	// record times of instance checking
	private Map<OWLClassExpression, Double> classTimeMap;

    private Map<OWLDataProperty, List<Double>> dataPropertyThresholdsMap;
    private Map<OWLDataProperty, Map<Double, Set<OWLNamedIndividual>>> dataPropertyInstancesMap;
    private Map<OWLDataProperty, Integer> dataPropertyStepMap;


	public ALCOperator(OWLReasoner reasoner, Set<OWLClass> classes, Set<OWLObjectProperty> properties,
                       Set<OWLDataProperty> dataProperties, OperatorConfig config) {
		this.reasoner = reasoner;	
		this.classes = classes;
		this.properties = properties;
        this.dataProperties = dataProperties;
        this.config = config;
		init();
	}
	
	
	private void init() {
		factory = new OWLDataFactoryImpl();
		initNegationMap();
		initClassHierachy();
		mapRedundantClassesAndProperties();
		initInstanceMap();
		initDataPropertyThresholds();
	}



    private void initDataPropertyThresholds() {
	    if (!config.useDataProperties) {
	        return;
        }
        dataPropertyThresholdsMap = new HashMap<>();
        dataPropertyInstancesMap = new HashMap<>();
        dataPropertyStepMap = new HashMap<>();
	    Set<OWLNamedIndividual> inds = reasoner.getRootOntology().getIndividualsInSignature();
	    for (OWLDataProperty prop : dataProperties) {
            Set<Double> thrSet = new HashSet<>();
            Map<Double, Set<OWLNamedIndividual>> instMap = new HashMap<>();
            dataPropertyInstancesMap.put(prop, instMap);
            for (OWLNamedIndividual ind : inds) {
                Set<OWLLiteral> dataPropertyValues = reasoner.getDataPropertyValues(ind, prop);
                for (OWLLiteral lit : dataPropertyValues) {
                    Double value = null;
                    if (lit.isInteger()) {
                        value = (double) lit.parseInteger();
                    } else if (lit.isFloat()) {
                        value = (double) lit.parseFloat();
                    } else if (lit.isDouble()) {
                        value = lit.parseDouble();
                    }
                    if (value != null) {
                        thrSet.add(value);
                        Set<OWLNamedIndividual> insts = instMap.get(value);
                        if (insts == null) {
                            insts = new HashSet<>();
                            instMap.put(value, insts);
                        }
                        insts.add(ind);
                    }
                }
            }
            List<Double> thrList = new ArrayList<>(thrSet);
            Collections.sort(thrList);
            dataPropertyThresholdsMap.put(prop, thrList);
            int step = thrList.size() / config.dataThresholdsNumber;
            step = (step <= 0) ? 1 : step;
            dataPropertyStepMap.put(prop, step);
        }
    }


	
	private void initInstanceMap() {
		classInstanceMap = new HashMap<>();

		// reasoning
	    if (config.useReasonerForAtomicClassInstances) {
	        classTimeMap = new HashMap<>();
            int count = 0;
            for (OWLClass cl : classes) {
                double t1 = System.nanoTime();
                Set<OWLNamedIndividual> instances = null;
                try {
                    instances = reasoner.getInstances(cl, false).getFlattened();
                } catch (Exception e) {
                    Out.p(e + DLMinerOutputI.CONCEPT_BUILDING_ERROR);
                }
                double t2 = System.nanoTime();
                double time = (t2 - t1)/1e9;
                classTimeMap.put(cl, time);
                Set<OWLNamedIndividual> copyInstances = new HashSet<>(instances);
                copyInstances.remove(null);
                classInstanceMap.put(cl, copyInstances);
                Out.p(++count + " / " + classes.size() + " classes are checked for instances");
            }
        }

        // owl:Thing
        OWLOntology ontology = reasoner.getRootOntology();
        OWLClass thing = factory.getOWLThing();
        if (!classInstanceMap.containsKey(thing)) {
            classInstanceMap.put(thing, ontology.getIndividualsInSignature());
        }

        // simple told assertions (positive and negative)
		Set<OWLAxiom> aboxAxioms = ontology.getABoxAxioms(true);
		for (OWLAxiom ax : aboxAxioms) {
			if (ax instanceof OWLClassAssertionAxiom) {
				OWLClassAssertionAxiom axiom = (OWLClassAssertionAxiom) ax;
				OWLClassExpression expr = axiom.getClassExpression();
                if (!expr.isAnonymous()
                        || (config.useNegation && expr instanceof OWLObjectComplementOf
                            && !((OWLObjectComplementOf) expr).getOperand().isAnonymous())) {
                    Set<OWLNamedIndividual> instances = classInstanceMap.get(expr);
                    if (instances == null) {
                        instances = new HashSet<>();
                        classInstanceMap.put(expr, instances);
                    }
                    OWLIndividual ind = axiom.getIndividual();
                    if (ind != null && ind.isNamed()) {
                        instances.add(ind.asOWLNamedIndividual());
                    }
                }
			}			
		}

		// disjoint classes
        if (config.checkDisjointness && config.useNegation) {
            for (OWLClass cl : classes) {
                Set<OWLNamedIndividual> instances = classInstanceMap.get(cl);
                if (!instances.isEmpty()) {
                    Set<OWLClass> disjCls = disjClassMap.get(cl);
                    if (disjCls != null) {
                        for (OWLClass disjCl : disjCls) {
                            OWLClassExpression negCl = negationMap.get(disjCl);
                            Set<OWLNamedIndividual> negInstances = classInstanceMap.get(negCl);
                            if (negInstances == null) {
                                negInstances = new HashSet<>();
                                classInstanceMap.put(negCl, negInstances);
                            }
                            negInstances.addAll(instances);
                        }
                    }
                }
            }
        }

	}
	
	

	private void initNegationMap() {
		if (!config.useNegation) {
			return;
		}
		negationMap = new HashMap<>();
		for (OWLClass cl : classes) {
			OWLClassExpression neg = factory.getOWLObjectComplementOf(cl);
			negationMap.put(cl, neg);
			negationMap.put(neg, cl);
		}
	}



	private void initClassHierachy() {
		classHierarchy = new HashMap<>();
		OWLClass thing = factory.getOWLThing();

		// class hierarchy
		if (config.checkRedundancy) {
            // owl:Thing
            classHierarchy.put(thing, getDirectSubClasses(thing));
            // classes
            for (OWLClass cl : classes) {
                classHierarchy.put(cl, getDirectSubClasses(cl));
            }
        } else {
            Set<OWLClassExpression> tsubs = new HashSet<>(classes);
            tsubs.remove(thing);
            classHierarchy.put(thing, tsubs);
        }

		// negations
		if (negationMap != null) {
			Set<OWLClassExpression> tsubs = classHierarchy.get(thing);
            if (config.checkRedundancy) {
                // owl:Thing
                Set<OWLClassExpression> nsups = getDirectSuperClasses(factory.getOWLNothing());
                for (OWLClassExpression nsup : nsups) {
                    tsubs.add(negationMap.get(nsup));
                }
                // classes
                for (OWLClass cl : classes) {
                    Set<OWLClassExpression> subs = null;
                    Set<OWLClassExpression> sups = getDirectSuperClasses(cl);
                    if (sups != null && !sups.isEmpty()) {
                        subs = new HashSet<>();
                        for (OWLClassExpression sup : sups) {
                            subs.add(negationMap.get(sup));
                        }
                    }
                    classHierarchy.put(negationMap.get(cl), subs);
                }
            } else {
                for (OWLClass cl : classes) {
                    tsubs.add(negationMap.get(cl));
                }
            }
		}
	}
	
	
	private Set<OWLClassExpression> getDirectSubClasses(OWLClassExpression expr) {
		Set<OWLClassExpression> subs = null;
		for (OWLClass sub : reasoner.getSubClasses(expr, true).getFlattened()) {
			if (classes.contains(sub)) {
				if (subs == null) {
					subs = new HashSet<>();
				}
				subs.add(sub);
			}
		}
		return subs;
	}
	
	
	private Set<OWLClassExpression> getDirectSuperClasses(OWLClassExpression expr) {
		Set<OWLClassExpression> sups = null;
		for (OWLClass sup : reasoner.getSuperClasses(expr, true).getFlattened()) {
			if (classes.contains(sup)) {
				if (sups == null) {
					sups = new HashSet<>();
				}
				sups.add(sup);
			}
		}
		return sups;
	}




	public Set<ALCNode> refine(ALCNode current) {
		Set<ALCNode> extensions = new HashSet<>();
		if (current.length() > config.maxLength || current.depth() > config.maxDepth) {
			return extensions;
		}
		// traverse
		List<CNode> cnodes = current.traverse();
		for (CNode cnode : cnodes) {
			ALCNode node = (ALCNode) cnode;
			extensions.addAll(refineNode(node, current));			
		}
		return extensions;
	}
	
	
	
	
	private Set<ALCNode> refineNode(ALCNode node, ALCNode current) {
		Set<ALCNode> extensions = new HashSet<>();
        if (current.length() > config.maxLength || current.depth() > config.maxDepth) {
            return extensions;
        }
        int length = current.isOWLThing() ? 0 : current.length();
        // refine labels
        extensions.addAll(refineLabels(node, current));
        // add object property restrictions
        if (length <= config.maxLength - 2) {
            // existential restrictions
            for (OWLObjectProperty prop : properties) {
                if (!config.checkRedundancy || !isRedundantExistential(prop, node)) {
                    extensions.add(getExistential(node, current, prop));
                }
            }
            // universal restrictions
            if (config.useUniversalRestriction) {
                for (OWLObjectProperty prop : properties) {
                    extensions.add(getUniversal(node, current, prop));
                }
            }
        }
        // refine data properties
        if (config.useDataProperties) {
            // refine data property values
            extensions.addAll(refineDataPropertyValues(node, current));
            // add data property edges
            if (length <= config.maxLength - 2) {
                for (OWLDataProperty prop : dataProperties) {
                    if (hasThresholds(prop)) {
                        if (!config.checkRedundancy || !isRedundantDataRestriction(node, prop, true)) {
                            extensions.add(getDataRestriction(node, current, prop, true));
                        }
                        if (!config.checkRedundancy || !isRedundantDataRestriction(node, prop, false)) {
                            extensions.add(getDataRestriction(node, current, prop, false));
                        }
                    }
                }
            }
        }
		return extensions;
	}


    private boolean isRedundantDataRestriction(ALCNode node,
                                               OWLDataProperty prop, boolean isLess) {
        if (node.getOutEdges() == null) {
            return false;
        }
        for (CEdge e : node.getOutEdges()) {
            if (e.label.equals(prop)) {
                if (isLess && e instanceof LDataEdge) {
                    return true;
                }
                if (!isLess && e instanceof GDataEdge) {
                    return true;
                }
            }
        }
        return false;
    }


    private boolean hasThresholds(OWLDataProperty prop) {
        List<Double> thresholds = dataPropertyThresholdsMap.get(prop);
        return thresholds != null && !thresholds.isEmpty();
    }



    private ALCNode getDataRestriction(ALCNode node, ALCNode current,
                                       OWLDataProperty prop, boolean isLess) {
        // clone the root
        ALCNode extension = current.clone();
        // find the equal node
        ALCNode equal = (ALCNode) extension.find(node);
        // refine the equal node
        DataEdge edge;
        List<Double> thresholds = dataPropertyThresholdsMap.get(prop);
        if (isLess) {
            // get last
            double value = thresholds.get(thresholds.size() - 1);
            NumericNode obj = new NumericNode(value);
            edge = new LDataEdge(equal, prop, obj);
        } else {
            // get first
            double value = thresholds.get(0);
            NumericNode obj = new NumericNode(value);
            edge = new GDataEdge(equal, prop, obj);
        }
        equal.addOutEdge(edge);
        // update the concept
        extension.updateConcept();
        return extension;
    }



    private Set<ALCNode> refineDataPropertyValues(ALCNode node, ALCNode current) {
        Set<ALCNode> extensions = new HashSet<>();
	    if (node.getOutEdges() == null) {
	        return extensions;
        }
        for (CEdge e : node.getOutEdges()) {
            ALCNode extension = null;
	        if (e instanceof DataEdge) {
                extension = refineDataPropertyValue((DataEdge)e, node, current);
            }
	        if (extension != null) {
                extensions.add(extension);
            }
        }
        return extensions;
    }



    private ALCNode refineDataPropertyValue(DataEdge e, ALCNode node, ALCNode current) {
	    if (!(e instanceof GDataEdge || e instanceof LDataEdge)) {
	        return null;
        }
        // clone the root
        ALCNode extension = current.clone();
        // find the equal node
        ALCNode eqNode = (ALCNode) extension.find(node);
        // find the equal edge
        CEdge eqEdge = null;
        for (CEdge edge : eqNode.getOutEdges()) {
            if (edge.equals(e) && edge.object.equals(e.object)) {
                eqEdge = edge;
            }
        }
        // refine the equal edge
        List<Double> thresholds = dataPropertyThresholdsMap.get(e.label);
        NumericNode ln = (NumericNode) eqEdge.object;
        double val = ln.value;
        int index = thresholds.indexOf(val);
        int step = dataPropertyStepMap.get(e.label);
        if (eqEdge instanceof GDataEdge) {
            // if last
            if (index + step >= thresholds.size()) {
                return null;
            }
            // get next
            eqEdge.object = new NumericNode(thresholds.get(index + step));
        }
        if (eqEdge instanceof LDataEdge) {
            // if first
            if (index - step < 0) {
                return null;
            }
            // get previous
            eqEdge.object = new NumericNode(thresholds.get(index - step));
        }
        // update the concept
        extension.updateConcept();
        return extension;
    }



    private boolean isRedundantExistential(
			OWLObjectProperty prop, ALCNode node) {
		return isDisjointWithPropertyDomains(prop, node);
	}
	
		
	



	
	
	
	


	private Set<ALCNode> refineLabels(ALCNode node, ALCNode current) {
		if (node.clabels.isEmpty() && node.dlabels.isEmpty()) {
			return refineLabelsEmpty(node, current);
		}
		return refineLabelsNonempty(node, current);			
	}
	
	
	
	private Set<ALCNode> refineLabelsNonempty(ALCNode node, ALCNode current) {
		// specialise classes
		Set<ALCNode> extensions = specialiseLabels(node, current);		
		if (current.length() <= config.maxLength - 2) {
			// add classes
			extensions.addAll(extendLabels(node, current));
		}		
		return extensions;
	}



	private Set<ALCNode> extendLabels(ALCNode node, ALCNode current) {
		Set<ALCNode> extensions = new HashSet<>();
		Set<OWLClassExpression> mgcs = classHierarchy.get(factory.getOWLThing());
		if (mgcs == null) {
			return extensions;
		}
		for (OWLClassExpression expr : mgcs) {
			// check redundancy
			if (config.checkRedundancy &&
                    isRedundantConjunctionForAddition(expr, node)) {
				continue;
			}			
			// add to extension
			extensions.add(getConjunction(expr, node, current));						
		}
		return extensions;
	}




    private ALCNode getConjunction(OWLClassExpression expr,
			ALCNode node, ALCNode current) {					
		// clone the root
		ALCNode extension = current.clone();					
		// find the equal node
		ALCNode equal = (ALCNode) extension.find(node);
		// extend the equal node				
		equal.clabels.add(expr);
        // update the concept
        extension.updateConcept();
		return extension;
	}
	
	
	
	
	private boolean isRedundantWithClassExpressions(OWLClassExpression expr, 
			Set<OWLClassExpression> labels) {
		if (labels.isEmpty()) {
			return false;
		}
		if (isRedundantClassFor(expr, labels)) {
			return true;
		}
		return isRedundantNegationFor(expr, labels);		
	}
	
	
	
	private boolean isRedundantNegationFor(OWLClassExpression expr, 
			Set<OWLClassExpression> labels) {
		if (negationMap == null || !expr.isAnonymous()) {
			return false;
		}
		// negations
		OWLClassExpression atomicExpr = negationMap.get(expr);
		if (atomicExpr == null) {
			return false;
		}
		if (labels.contains(atomicExpr)) {
			return true;
		}
		if (equivClassMap.isEmpty() && subClassMap.isEmpty() && superClassMap.isEmpty()) {
			return false;
		}
		Set<OWLClass> equivClasses = equivClassMap.get(atomicExpr);
		if (equivClasses == null) {
			return false;
		}
		Set<OWLClass> subClasses = subClassMap.get(atomicExpr);
		Set<OWLClass> superClasses = superClassMap.get(atomicExpr);
		// compare to other classes		
		for (OWLClassExpression label : labels) {			
			if (!label.isAnonymous()) {
				continue;
			}
			OWLClassExpression atomicLabel = negationMap.get(label);
			if (atomicLabel == null) {
				continue;
			}			
			if (equivClasses.contains(atomicLabel) 
					|| subClasses.contains(atomicLabel) 
					|| superClasses.contains(atomicLabel)) {
				return true;
			}			
		}
		return false;
	}



	private boolean isRedundantClassFor(OWLClassExpression expr, 
			Set<OWLClassExpression> labels) {
		if (expr.isAnonymous()) {
			return false;
		}
		if (labels.contains(expr)) {
			return true;
		}
		if (equivClassMap.isEmpty() && subClassMap.isEmpty() && superClassMap.isEmpty()) {
			return false;
		}
		Set<OWLClass> equivClasses = equivClassMap.get(expr);
		if (equivClasses == null) {
			// negation
			return false;
		}
		Set<OWLClass> subClasses = subClassMap.get(expr);
		Set<OWLClass> superClasses = superClassMap.get(expr);
		// compare to other classes		
		for (OWLClassExpression label : labels) {			
			if (equivClasses.contains(label) || subClasses.contains(label) || superClasses.contains(label)) {
				return true;
			}			
		}
		return false;
	}
	
	
	
	private boolean isDisjointWithClassExpressions(OWLClassExpression expr, 
			Set<OWLClassExpression> labels) {
		if (labels.isEmpty()) {
			return false;
		}
		if (disjClassMap.isEmpty()) {
			return false;
		}
		Set<OWLClass> disjClasses = disjClassMap.get(expr);
		if (disjClasses == null || disjClasses.isEmpty()) {
			return false;
		}
		for (OWLClassExpression label : labels) {
			if (disjClasses.contains(label)) {
				return true;
			}			
		}					
		return false;	
	}

	

	



	private Set<ALCNode> specialiseLabels(ALCNode node, ALCNode current) {
		Set<ALCNode> extensions = new HashSet<>();
		// conjunctions
		for (OWLClassExpression expr : node.clabels) {
			Set<OWLClassExpression> subs = classHierarchy.get(expr);
			// only if there are subclasses
			if (subs != null && !subs.isEmpty()) {
				for (OWLClassExpression sub : subs) {
					// check redundancy
					if (config.checkRedundancy
                            && isRedundantConjunctionForSpecialisation(sub, node)) {
						continue;
					}								
					// add to extensions
					extensions.add(replaceConjunction(expr, sub, node, current));
				}
			}
		}
		// disjunctions
		for (OWLClassExpression expr : node.dlabels) {
			Set<OWLClassExpression> subs = classHierarchy.get(expr);
			// if there are subclasses, replace disjunction
			if (subs != null && !subs.isEmpty()) {				
				// never check redundancy (loss of concepts)					
				// add to extensions
				extensions.addAll(replaceDisjunction(expr, node, current));
			} else {
				// drop disjunction
				ALCNode extension = dropDisjunction(expr, node, current);
				if (extension != null) {
					extensions.add(extension);
				}
			}
			
		}		
		return extensions;
	}
	
	
	


	
	private boolean isRedundantConjunctionForSpecialisation(OWLClassExpression expr, ALCNode node) {
		return isDisjointWithClassExpressions(expr, node.clabels)
                || isInsufficientConjunctionForNode(expr, node)
				|| isDisjointWithPropertyDomains(expr, node)
				|| isDisjointWithPropertyRanges(expr, node);
	}
	
	
	
	private boolean isRedundantConjunctionForAddition(OWLClassExpression expr, ALCNode node) {
		return isRedundantWithClassExpressions(expr, node.clabels)				
				|| isRedundantConjunctionForSpecialisation(expr, node);
	}


    private boolean isInsufficientConjunctionForNode(OWLClassExpression expr, ALCNode node) {
	    // top level
	    if (node.getInEdges() == null || node.getInEdges().isEmpty()) {
            Set<OWLNamedIndividual> insts = classInstanceMap.get(expr);
            if (insts == null || insts.size() < config.minSupport) {
	            return true;
            }
        }
        return false;
    }
	
	
	
	private ALCNode dropDisjunction(OWLClassExpression expr, ALCNode node, ALCNode current) {
		// if {A}, then do not drop A because results in the empty set (owl:Thing)
		if (node.dlabels.size() <= 1 && node.clabels.isEmpty()) {
			return null;
		}
		// if {A, B} and expr=A, then drop A and B, 
		// then move B to conjunctions if not redundant
		OWLClassExpression remain = null;
		if (node.dlabels.size() == 2) {
			for (OWLClassExpression disj : node.dlabels) {
				if (!disj.equals(expr)) {
					remain = disj;
					break;
				}
			}
			if (config.checkRedundancy
                    && isRedundantConjunctionForAddition(remain, node)) {
				return null;
			}
		}
		// clone the root
		ALCNode extension = current.clone();					
		// find the equal node
		ALCNode equal = (ALCNode) extension.find(node);		
		// remove		
		equal.dlabels.remove(expr);		
		if (equal.dlabels.size() == 1) {
			equal.clabels.add(remain);
			equal.dlabels.remove(remain);
		}
        // update the concept
        extension.updateConcept();
		return extension;
	}



	private Set<ALCNode> replaceDisjunction(OWLClassExpression expr, ALCNode node, ALCNode current) {		
		// clone the root
		ALCNode clone = current.clone();					
		// find the equal node
		ALCNode equal = (ALCNode) clone.find(node);
		// remove the disjunction to be replaced
		equal.dlabels.remove(expr);
		// update the concept
        clone.updateConcept();
		// get disjunctions that satisfy maximal length
		Set<Set<OWLClassExpression>> disjs = generateDisjunctionsFor(expr, clone.length());
		if (disjs.isEmpty()) {
			return new HashSet<>();
		}		
		// extend labels		
		return extendDisjunctions(disjs, equal, clone);		
	}
	
	
	
	
	private ALCNode replaceConjunction(OWLClassExpression expr, 
			OWLClassExpression sub, ALCNode node, ALCNode current) {
		// clone the root
		ALCNode extension = current.clone();					
		// find the equal node
		ALCNode equal = (ALCNode) extension.find(node);
		// extend the equal node				
		equal.clabels.add(sub);
		// remove the conjunction
		equal.clabels.remove(expr);
		// update the concept
        extension.updateConcept();
		return extension;
	}
	



	private Set<ALCNode> refineLabelsEmpty(ALCNode node, ALCNode current) {		
		// get disjunctions that satisfy the maximal length
        int currentLength;
        if (node.isOWLThing()) {
            currentLength = current.length()-1;
        } else {
            currentLength = current.length();
        }
		Set<Set<OWLClassExpression>> disjs =
                generateDisjunctionsFor(factory.getOWLThing(), currentLength);
		if (disjs.isEmpty()) {
			return new HashSet<>();
		}
		// extend labels			
		return extendDisjunctions(disjs, node, current);
	}
	
	
	
	private Set<ALCNode> extendDisjunctions(Set<Set<OWLClassExpression>> disjs,
			ALCNode node, ALCNode current) {
		Set<ALCNode> extensions = new HashSet<>();
		for (Set<OWLClassExpression> disj : disjs) {
			if (disj.isEmpty()) {
				continue;
			}
			// clone the root
			ALCNode extension = current.clone();					
			// find the equal node
			ALCNode equal = (ALCNode) extension.find(node);
			// add disjunction classes
			if (disj.size() == 1) {
				OWLClassExpression disjExpr = null;
				for (OWLClassExpression expr : disj) {
					disjExpr = expr;
				}					
				if (!config.checkRedundancy ||
                        !isRedundantConjunctionForSpecialisation(disjExpr, equal)) {
                    equal.clabels.add(disjExpr);
				}
			} else {
				equal.dlabels.addAll(disj);
			}
            // update the concept
            extension.updateConcept();
			// add to extension
			extensions.add(extension);
		}
		return extensions;
	}
	
	
	
	
	private ALCNode getExistential(ALCNode node, ALCNode current, 
			OWLObjectProperty prop) {
		// clone the root
		ALCNode extension = current.clone();					
		// find the equal node
		ALCNode equal = (ALCNode) extension.find(node);
		// extend the equal node
		Set<OWLClassExpression> l1 = new HashSet<>(2);
		Set<OWLClassExpression> l2 = new HashSet<>(2);
		ALCNode empty = new ALCNode(l1, l2);
		SomeEdge edge = new SomeEdge(equal, prop, empty);
		equal.addOutEdge(edge);
        // update the concept
        extension.updateConcept();
		return extension;
	}
	
	
	
	private ALCNode getUniversal(ALCNode node, ALCNode current, 
			OWLObjectProperty prop) {
		// clone the root
		ALCNode extension = current.clone();					
		// find the equal node
		ALCNode equal = (ALCNode) extension.find(node);
		// extend the equal node
		Set<OWLClassExpression> l1 = new HashSet<>(2);
		Set<OWLClassExpression> l2 = new HashSet<>(2);
		ALCNode empty = new ALCNode(l1, l2);
		OnlyEdge edge = new OnlyEdge(equal, prop, empty);
		equal.addOutEdge(edge);
        // update the concept
        extension.updateConcept();
		return extension;
	}



	private Set<Set<OWLClassExpression>> generateDisjunctionsFor(
			OWLClassExpression expr, int currentLength) {
		Set<Set<OWLClassExpression>> disjs = new HashSet<>();
		int lengthToFill = config.maxLength - currentLength;
		if (lengthToFill <= 0) {
			return disjs;			
		}
		Set<OWLClassExpression> mgcs = classHierarchy.get(expr);		
		if (mgcs == null) {
			return disjs;
		}
		// only atomic classes		
		for (OWLClassExpression mgc : mgcs) {
			if (classes.contains(mgc)) {
				Set<OWLClassExpression> disj = new HashSet<>();
				disj.add(mgc);
				disjs.add(disj);
			}
		}
		if (lengthToFill == 1) {
			return disjs;
		}
		// only negations
		if (config.useNegation) {
			for (OWLClassExpression mgc : mgcs) {
				if (!classes.contains(mgc)) {
					Set<OWLClassExpression> disj = new HashSet<>();
					disj.add(mgc);
					disjs.add(disj);
				}
			}
		}
		// if disjunctions are not needed
		if (!config.useDisjunction || lengthToFill == 2) {
			return disjs;
		}		
		// get all combinations		
		return generateCombinations(mgcs, lengthToFill);
	}



	private int labelLength(Set<OWLClassExpression> comb) {
		int len = comb.size() - 1;
		for (OWLClassExpression cl : comb) {
			len += LengthMetric.length(cl);
		}
		return len;
	}



	private Set<Set<OWLClassExpression>> generateCombinations(
			Set<OWLClassExpression> mgcs, int len) {
		Set<Set<OWLClassExpression>> combs = new HashSet<>();
		for (OWLClassExpression mgc : mgcs) {			
			Set<OWLClassExpression> comb = new HashSet<>();
			comb.add(mgc);
			combs.add(comb);			
		}
		if (mgcs.size() == 1) {
			return combs;
		}
		int num = (len % 2 == 0) ? len/2 : len/2+1;
		for (int i=2; i<=num; i++) {
			Set<Set<OWLClassExpression>> newCombs = new HashSet<>();
			for (Set<OWLClassExpression> comb : combs) {
				if (comb.size() == i-1) {
					for (OWLClassExpression mgc : mgcs) {
						// do not add redundant combinations
						if (config.checkRedundancy &&
                                isRedundantWithClassExpressions(mgc, comb)) {
							continue;
						}
						// do not add concepts with no instances
                        if (config.checkRedundancy) {
                            Set<OWLNamedIndividual> insts = classInstanceMap.get(mgc);
                            if (insts == null || insts.isEmpty()) {
                                continue;
                            }
                        }
						Set<OWLClassExpression> newComb = new HashSet<>(comb);
						newComb.add(mgc);
						// add combinations that satisfy length
						if (labelLength(newComb) <= len) {
							newCombs.add(newComb);
						}
					}
				}
			}
			combs.addAll(newCombs);
		}		
		return combs;
	}



	private boolean areInstancesSubsumed(Set<OWLNamedIndividual> insts, 
			Set<OWLClassExpression> exprs) {
		for (OWLClassExpression expr : exprs) {
			Set<OWLNamedIndividual> exprInsts = classInstanceMap.get(expr);
			if (exprInsts != null && exprInsts.containsAll(insts)) {
				return true;
			}
		}
		return false;
	}



	
	/*public boolean isRedundantNode(ALCNode node) {
		LinkedList<CNode> childs = node.traverse();	
		for (CNode child : childs) {
			ALCNode alcChild = (ALCNode) child;
			// owl:Thing is never redundant
			// check conjunctions
			for (OWLClassExpression expr : alcChild.clabels) {
				if (isRedundantConjunction(expr, alcChild)) {
					return true;
				}
			}
			// check disjunctions
			for (OWLClassExpression expr : alcChild.dlabels) {
				if (isRedundantDisjunction(expr, alcChild)) {
					return true;
				}
			}
		}
		return false;
	}*/


	
	private boolean isRedundantConjunction(OWLClassExpression expr, ALCNode node) {
		return isRedundantConjunctionForSpecialisation(expr, node)
				|| isRedundantWithClassExpressions(expr, node.dlabels)				
				|| isRedundantWithPropertyDomains(expr, node)
				|| isRedundantWithPropertyRanges(expr, node);
	}
	



	private boolean isRedundantDisjunction(OWLClassExpression expr, ALCNode node) {
		Set<OWLClassExpression> dlabels = new HashSet<>(node.dlabels);
		dlabels.remove(expr);
		return isRedundantWithClassExpressions(expr, dlabels);
	}


	

	/**
	 * @return the classInstanceMap
	 */
	public Map<OWLClassExpression, Set<OWLNamedIndividual>> getClassInstanceMap() {
		return classInstanceMap;
	}



	public Set<ALCNode> getAtomicNodes() {
		Set<ALCNode> extensions = new HashSet<>();		
		for (OWLClass cl : classes) {
			Set<OWLClassExpression> clabels = new HashSet<>(2);
			clabels.add(cl);
			Set<OWLClassExpression> dlabels = new HashSet<>(2);
			ALCNode node = new ALCNode(clabels, dlabels);
			extensions.add(node);
		}
		return extensions;
	}



	public Set<OWLObjectPropertyExpression> getEquivalentObjectProperties(
			OWLObjectPropertyExpression property) {		
		return equivPropertyMap.get(property);
	}



	public Set<OWLObjectPropertyExpression> getSuperObjectProperties(
			OWLObjectPropertyExpression property) {		
		return superPropertyMap.get(property);
	}



	public Set<OWLObjectPropertyExpression> getInverseObjectProperties(
			OWLObjectPropertyExpression property) {
		return invPropertyMap.get(property);
	}



    public Map<OWLDataProperty, List<Double>> getDataPropertyThresholdsMap() {
        return dataPropertyThresholdsMap;
    }


    public Map<OWLDataProperty, Map<Double, Set<OWLNamedIndividual>>> getDataPropertyInstancesMap() {
        return dataPropertyInstancesMap;
    }


    public Map<OWLDataProperty, Integer> getDataPropertyStepMap() {
        return dataPropertyStepMap;
    }


    public Double getTimeByClass(OWLClassExpression cl) {
	    if (classTimeMap == null) {
	        return  null;
        }
	    return classTimeMap.get(cl);
    }


}
