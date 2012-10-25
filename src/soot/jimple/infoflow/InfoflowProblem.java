package soot.jimple.infoflow;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import soot.Local;
import soot.NullType;
import soot.PointsToAnalysis;
import soot.PointsToSet;
import soot.PrimType;
import soot.Scene;
import soot.SootField;
import soot.SootFieldRef;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.DefinitionStmt;
import soot.jimple.FieldRef;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.ReturnStmt;
import soot.jimple.StaticFieldRef;
import soot.jimple.Stmt;
import soot.jimple.infoflow.source.DefaultSourceManager;
import soot.jimple.infoflow.source.SourceManager;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JCastExpr;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.interproc.ifds.FlowFunction;
import soot.jimple.interproc.ifds.FlowFunctions;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.flowfunc.Identity;
import soot.jimple.interproc.ifds.template.DefaultIFDSTabulationProblem;
import soot.jimple.interproc.ifds.template.JimpleBasedInterproceduralCFG;
import soot.toolkits.scalar.Pair;
import soot.jimple.infoflow.data.ExtendedValue;

public class InfoflowProblem extends DefaultIFDSTabulationProblem<Pair<Value, Value>, InterproceduralCFG<Unit, SootMethod>> {

	final Set<Unit> initialSeeds = new HashSet<Unit>();
	final SourceManager sourceManager;
	final List<String> sinks;
	final HashMap<String, List<String>> results;
	final Pair<Value, Value> zeroValue = new Pair<Value, Value>(new JimpleLocal("zero", NullType.v()), null);

	static boolean DEBUG = false;
	
	

	public FlowFunctions<Unit, Pair<Value, Value>, SootMethod> createFlowFunctionsFactory() {
		return new FlowFunctions<Unit, Pair<Value, Value>, SootMethod>() {

			public FlowFunction<Pair<Value, Value>> getNormalFlowFunction(Unit src, Unit dest) {
				if (src instanceof Stmt && DEBUG) {
					System.out.println("Normal: " + ((Stmt) src));
				}

				if (src instanceof soot.jimple.internal.JIfStmt) {
					soot.jimple.internal.JIfStmt ifStmt = (soot.jimple.internal.JIfStmt) src;
					src = ifStmt.getTarget();
				}

				if (src instanceof AssignStmt) {
					AssignStmt assignStmt = (AssignStmt) src;
					Value right = assignStmt.getRightOp();
					Value left = assignStmt.getLeftOp();

					if (left instanceof ArrayRef) {
						left = ((ArrayRef) left).getBase();
					}

					if (right instanceof Value && left instanceof Value) {
						final Value leftValue = left;
						final Value rightValue = right;

						return new FlowFunction<Pair<Value, Value>>() {

							public Set<Pair<Value, Value>> computeTargets(Pair<Value, Value> source) {
								boolean addLeftValue = false;
								//debug:
								if(rightValue.toString().contains("$r0.<java.util.LinkedList$Node: java.lang.Object item>")){
									leftValue.toString();
								}
								
								// normal check for infoflow
								if (rightValue instanceof JVirtualInvokeExpr || !source.equals(zeroValue)) {
									PointsToAnalysis pta = Scene.v().getPointsToAnalysis();
									if (source.getO1() instanceof InstanceFieldRef && rightValue instanceof InstanceFieldRef) {
										InstanceFieldRef rightRef = (InstanceFieldRef) rightValue;
										InstanceFieldRef sourceRef = (InstanceFieldRef) source.getO1();

										if (rightRef.getField().getName().equals(sourceRef.getField().getName())) {
											Local rightBase = (Local) rightRef.getBase();
											
											PointsToSet ptsRight = pta.reachingObjects(rightBase);
											Local sourceBase = (Local) sourceRef.getBase();
											PointsToSet ptsSource2 = pta.reachingObjects(sourceBase);
											if (ptsRight.hasNonEmptyIntersection(ptsSource2)) {
												addLeftValue = true;
											}
											
										}
									}

									if (rightValue instanceof InstanceFieldRef && source.getO1() instanceof Local && !source.equals(zeroValue) ) {
										PointsToSet ptsSource = pta.reachingObjects((Local) source.getO1());
										InstanceFieldRef rightRef = (InstanceFieldRef) rightValue;
										PointsToSet ptsResult = pta.reachingObjects(ptsSource, rightRef.getField());

										if (!ptsResult.isEmpty()) {
											addLeftValue = true;
										}

									}

									if (rightValue instanceof StaticFieldRef && source.getO1() instanceof StaticFieldRef) {
										StaticFieldRef rightRef = (StaticFieldRef) rightValue;
										StaticFieldRef sourceRef = (StaticFieldRef) source.getO1();
										if (rightRef.getFieldRef().name().equals(sourceRef.getFieldRef().name()) && rightRef.getFieldRef().declaringClass().equals(sourceRef.getFieldRef().declaringClass())) {
											addLeftValue = true;
										}
									}

									if (rightValue instanceof ArrayRef) {
										Local rightBase = (Local) ((ArrayRef) rightValue).getBase();
										if (source.getO1().equals(rightBase) || (source.getO1() instanceof Local && pta.reachingObjects(rightBase).hasNonEmptyIntersection(pta.reachingObjects((Local) source.getO1())))) {
											addLeftValue = true;
										}
									}
									if (rightValue instanceof JCastExpr) {
										if (source.getO1().equals(((JCastExpr) rightValue).getOpBox().getValue())) {
											addLeftValue = true;
										}
									}
									// TODO: Change and see if is possible by just using the native call
//									if (rightValue instanceof JVirtualInvokeExpr) {
//										if (((JVirtualInvokeExpr) rightValue).getMethodRef().name().equals("clone") || ((JVirtualInvokeExpr) rightValue).getMethodRef().name().equals("concat")) {
//											if (source.getO1().equals(((JVirtualInvokeExpr) rightValue).getBaseBox().getValue())) {
//												//addLeftValue = true;
//											}
//										}
//									}

									// generic case, is true for Locals, ArrayRefs that are equal etc..
									if (source.getO1().equals(rightValue)) {
										addLeftValue = true;
									}
									// also check if there are two arrays (or anything else?) that point to the same..
									if (source.getO1() instanceof Local && rightValue instanceof Local) {
										PointsToSet ptsRight = pta.reachingObjects((Local) rightValue);
										PointsToSet ptsSource = pta.reachingObjects((Local) source.getO1());
										if (ptsRight.hasNonEmptyIntersection(ptsSource)) {
											addLeftValue = true;
										}
									}

									// if one of them is true -> add leftValue
									if (addLeftValue) {
										
										Set<Pair<Value, Value>> res = new HashSet<Pair<Value, Value>>();
										res.add(source);
										ExtendedValue val = new ExtendedValue(source.getO2());
										val.addHistorie(source.getO1());
										res.add(new Pair<Value, Value>(leftValue, val));
										return res;
									}
								}
								return Collections.singleton(source);

							}
						};
					}

				}
				return Identity.v();
			}

			public FlowFunction<Pair<Value, Value>> getCallFlowFunction(Unit src, final SootMethod dest) {

				final Stmt stmt = (Stmt) src;
				
				final InvokeExpr ie = stmt.getInvokeExpr();
				if (DEBUG) {
					System.out.println("Call " + ie);
				}
				final List<Value> callArgs = ie.getArgs();
				final List<Value> paramLocals = new ArrayList<Value>();
				for (int i = 0; i < dest.getParameterCount(); i++) {
					paramLocals.add(dest.getActiveBody().getParameterLocal(i));
				}
				return new FlowFunction<Pair<Value, Value>>() {

					public Set<Pair<Value, Value>> computeTargets(Pair<Value, Value> source) {
						
						Set<Pair<Value, Value>> res = new HashSet<Pair<Value, Value>>();
						if(callArgs.contains(source.getO1())){
							for(int i=0; i<callArgs.size();i++){
								if(callArgs.get(i).equals(source.getO1())){
									ExtendedValue val = new ExtendedValue(source.getO2());
									val.addHistorie(source.getO1());
									res.add(new Pair<Value, Value>(paramLocals.get(i), val));
								}
							}
							if(sinks.contains(dest.toString())){
								if(!results.containsKey(dest.toString())){
									List<String> list = new ArrayList<String>();
									list.add(source.getO2().toString());
									results.put(dest.toString(), list);
								} else{
									results.get(dest.toString()).add(source.getO2().toString());
								}
							}
						}
						if (source.getO1() instanceof FieldRef) {
							// if (tempFieldRef.getField().getDeclaringClass().equals(dest.getDeclaringClass())) { // not enough because might be used in this class...
							res.add(source);
							
						}

						return res;
					}
				};
			}

			public FlowFunction<Pair<Value, Value>> getReturnFlowFunction(Unit callSite, SootMethod callee, Unit exitStmt, Unit retSite) {
				final SootMethod calleeMethod = callee;
				
				if (exitStmt instanceof Stmt & DEBUG) {
					System.out.println("ReturnExit: " + ((Stmt) exitStmt));
					System.out.println("ReturnStart: " + callSite.toString());
				}

				if (exitStmt instanceof ReturnStmt) {
					ReturnStmt returnStmt = (ReturnStmt) exitStmt;
					Value op = returnStmt.getOp();
					if (op instanceof Value) {
						if (callSite instanceof DefinitionStmt) {
							DefinitionStmt defnStmt = (DefinitionStmt) callSite;
							Value leftOp = defnStmt.getLeftOp();
							final Value tgtLocal = leftOp;
							final Value retLocal = op;
							return new FlowFunction<Pair<Value, Value>>() {

								public Set<Pair<Value, Value>> computeTargets(Pair<Value, Value> source) {
									
									Set<Pair<Value, Value>> res = new HashSet<Pair<Value, Value>>();
									if (source.getO1() instanceof FieldRef) {
										res.add(source);
									}
									if (source.getO1() == retLocal){
										ExtendedValue val = new ExtendedValue(source.getO2());
										val.addHistorie(source.getO1());
										res.add(new Pair<Value, Value>(tgtLocal, val));
									}
									return res;
								}

							};
						}

					}
				}

				return new FlowFunction<Pair<Value, Value>>() {

					public Set<Pair<Value, Value>> computeTargets(Pair<Value, Value> source) {
						Set<Pair<Value, Value>> res = new HashSet<Pair<Value, Value>>();
						if (source.getO1() instanceof Local) {
							PointsToAnalysis pta = Scene.v().getPointsToAnalysis();
							PointsToSet ptsRight = pta.reachingObjects((Local) source.getO1());

							for (SootField globalField : calleeMethod.getDeclaringClass().getFields()) {
								if (globalField.isStatic()) {
									PointsToSet ptsGlobal = pta.reachingObjects(globalField);
									if (ptsRight.hasNonEmptyIntersection(ptsGlobal)) {
										ExtendedValue val = new ExtendedValue(source.getO2());
										val.addHistorie(source.getO1());
										res.add(new Pair<Value, Value>(Jimple.v().newStaticFieldRef(globalField.makeRef()), val));
									}
								} else {
									if (!calleeMethod.isStatic()) { // otherwise runtime-exception
										PointsToSet ptsGlobal = pta.reachingObjects(calleeMethod.getActiveBody().getThisLocal(), globalField);
										if (ptsGlobal.hasNonEmptyIntersection(ptsRight)) {
											Local thisL = calleeMethod.getActiveBody().getThisLocal();
											SootFieldRef ref = globalField.makeRef();
											InstanceFieldRef fRef = Jimple.v().newInstanceFieldRef(thisL, ref);
											ExtendedValue val = new ExtendedValue(source.getO2());
											val.addHistorie(source.getO1());
											res.add(new Pair<Value, Value>(fRef, val));
										} //maybe check for duplicates here (is already in source..?
									}
								}

							}
						}
						if (source.getO1() instanceof FieldRef) {
							res.add(source);
						}
						return res;
					}

				};
			}

			public FlowFunction<Pair<Value, Value>> getCallToReturnFlowFunction(Unit call, Unit returnSite) {
				if (DEBUG) {
					System.out.println("C2R c: " + call);
					System.out.println("C2R r: " + returnSite);
				}
				
				if (call instanceof InvokeStmt && ((InvokeStmt) call).getInvokeExpr().getMethod().isNative()) {
					final InvokeStmt iStmt = (InvokeStmt) call;
					// Testoutput to collect all native calls from different runs of the analysis:
					try {
						FileWriter fstream = new FileWriter("nativeCalls.txt", true);
						BufferedWriter out = new BufferedWriter(fstream);
						out.write(iStmt.getInvokeExpr().getMethod().toString() + "\n");
						out.close();
					} catch (IOException e) {
						e.printStackTrace();
					}

					final List<Value> callArgs = iStmt.getInvokeExpr().getArgs();
					final List<Value> paramLocals = new ArrayList<Value>();
					for (int i = 0; i < iStmt.getInvokeExpr().getArgCount(); i++) {
						Value argValue = iStmt.getInvokeExpr().getArg(i);
						if (!(argValue.getType() instanceof PrimType)) {
							paramLocals.add(iStmt.getInvokeExpr().getArg(i));
						}
					}
					return new FlowFunction<Pair<Value, Value>>() {

						public Set<Pair<Value, Value>> computeTargets(Pair<Value, Value> source) {

							if(callArgs.contains(source.getO1())){
								// "res.add(new Pair<Value, Value>(paramLocals.get(argIndex), source.getO2()));" is not enough:
								// java uses call by value, but fields of complex objects can be changed (and tainted), so use this conservative approach:
								Set<Pair<Value, Value>> res = new HashSet<Pair<Value, Value>>();
								for (int i = 0; i < paramLocals.size(); i++) {
									ExtendedValue val = new ExtendedValue(source.getO2());
									val.addHistorie(source.getO1());
									res.add(new Pair<Value, Value>(paramLocals.get(i), val));
								}
								return res;
							}
							return Collections.emptySet();
						}
					};
				}
				if (call instanceof JAssignStmt) {
					final JAssignStmt stmt = (JAssignStmt) call;

					if (sourceManager.isSourceMethod(stmt.getInvokeExpr().getMethod())) {
						return new FlowFunction<Pair<Value, Value>>() {

							@Override
							public Set<Pair<Value, Value>> computeTargets(Pair<Value, Value> source) {
								Set<Pair<Value, Value>> res = new HashSet<Pair<Value, Value>>();
								res.add(source);
								ExtendedValue val = new ExtendedValue(stmt.getInvokeExpr());
								res.add(new Pair<Value, Value>(stmt.getLeftOp(), val));
								return res;
							}
						};
					}
				}
				return Identity.v();
			}
		};
	}

	public InfoflowProblem(List<String> sourceList, List<String> sinks) {
		super(new JimpleBasedInterproceduralCFG());
		sourceManager = new DefaultSourceManager(sourceList);
		this.sinks = sinks;
		results = new HashMap<String, List<String>>();
	}

	
	public InfoflowProblem(InterproceduralCFG<Unit, SootMethod> icfg, List<String> sourceList, List<String> sinks) {
		super(icfg);
		sourceManager = new DefaultSourceManager(sourceList);
		this.sinks = sinks;
		results = new HashMap<String, List<String>>();
	}

	public Pair<Value, Value> createZeroValue() {
		if (zeroValue == null)
			return new Pair<Value, Value>(new JimpleLocal("zero", NullType.v()), new ExtendedValue(null));
		return zeroValue;
	}

	@Override
	public Set<Unit> initialSeeds() {
		return initialSeeds;

	}
}
