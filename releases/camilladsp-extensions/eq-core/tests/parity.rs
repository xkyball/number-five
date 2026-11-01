#[allow(dead_code)] mod baseline {include!("baseline.rs.fixture");}
use no5_eq_core as core;
#[test]
fn byte_bridge_keeps_unedited_binary64_coordinates_exact() {
 for delta in 0..32 {
  let frequency=f64::from_bits(1000.0f64.to_bits()+delta);
  let previous=core::Preset{id:"bridge".into(),name:"Bridge".into(),routing_template:"stereo_4_1".into(),
   eq_enabled:true,reserve_db:12.0,room:core::Room{amount:0.0,size:0.5},
   eq_points:vec![core::Point{id:"peer".into(),frequency_hz:frequency,gain_db:0.0,q:10.0,width_locked:false},
    core::Point{id:"edited".into(),frequency_hz:10000.0,gain_db:0.0,q:10.0,width_locked:false}]};
  let mut requested=previous.clone();requested.eq_points[1].gain_db=0.5;
  let input=serde_json::to_vec(&serde_json::json!({"kind":"project","previous":previous,"requested":requested,
   "editedPointId":"edited","experimental":false})).unwrap();
  let actual=core::process(&input);
  let expected=serde_json::to_vec(&serde_json::json!({"policy":core::policy(),"preset":requested})).unwrap();
  assert_eq!(actual,expected,"unrelated peer changed across UTF-8 bridge at offset {delta}");
 }
}
fn preset(reserve:f64,count:usize)->serde_json::Value{
 serde_json::json!({"id":"test","name":"Test","routingTemplate":"stereo_4_1","eqEnabled":true,"reserveDb":reserve,"room":{"amount":0.0,"size":0.5},"eqPoints":(0..count).map(|i|serde_json::json!({"id":format!("p{i}"),"frequencyHz":30.0*500.0f64.powf(i as f64/(count.max(2)-1)as f64),"gainDb":0.0,"q":10.0,"widthLocked":false})).collect::<Vec<_>>()})
}
#[test] fn old_and_shared_projection_and_validation_agree(){
 for reserve in [6.0,12.0,18.0]{for experimental in [false,true]{for count in [1,16]{
 let previous=preset(reserve,count);let mut request=previous.clone();request["eqPoints"][0]["gainDb"]=serde_json::json!(reserve+if experimental{3.0}else{0.0});
 let a=core::project_eq_budget(&serde_json::from_value(previous.clone()).unwrap(),&serde_json::from_value(request.clone()).unwrap(),Some("p0"),experimental).map(|p|serde_json::to_value(p).unwrap());
 let b=baseline::project_eq_budget(&serde_json::from_value(previous).unwrap(),&serde_json::from_value(request).unwrap(),Some("p0"),experimental).map(|p|serde_json::to_value(p).unwrap());assert_eq!(a,b);
 }}}
}
#[test] fn crossing_all_signs_and_width_are_identical(){
 for gain in [-6.0,0.0,3.0]{let mut previous=preset(12.0,2);previous["eqPoints"][0]["gainDb"]=serde_json::json!(gain);
 for field in ["frequencyHz","q"]{let mut request=previous.clone();request["eqPoints"][0][field]=serde_json::json!(if field=="q"{0.3}else{20000.0});
 let a=core::project_eq_budget(&serde_json::from_value(previous.clone()).unwrap(),&serde_json::from_value(request.clone()).unwrap(),Some("p0"),false).map(|p|serde_json::to_value(p).unwrap());
 let b=baseline::project_eq_budget(&serde_json::from_value(previous.clone()).unwrap(),&serde_json::from_value(request).unwrap(),Some("p0"),false).map(|p|serde_json::to_value(p).unwrap());assert_eq!(a,b);
 }}
}
#[test] fn bulk_reduction_and_sums_use_original_dense_policy(){
 let mut previous=preset(18.0,2);previous["eqPoints"][0]["gainDb"]=serde_json::json!(17.0);previous["eqPoints"][1]["gainDb"]=serde_json::json!(-6.0);
 for reserve in [6.0,12.0,18.0]{let mut requested=previous.clone();requested["reserveDb"]=serde_json::json!(reserve);
 let a=core::project_eq_budget(&serde_json::from_value(previous.clone()).unwrap(),&serde_json::from_value(requested.clone()).unwrap(),None,false).map(|p|serde_json::to_value(p).unwrap());
 let b=baseline::project_eq_budget(&serde_json::from_value(previous.clone()).unwrap(),&serde_json::from_value(requested).unwrap(),None,false).map(|p|serde_json::to_value(p).unwrap());assert_eq!(a,b);
 }
}
#[test] fn bridge_is_bounded_and_does_not_accept_bad_requests(){assert!(serde_json::from_slice::<serde_json::Value>(&core::process(b"{} ")).unwrap().get("error").is_some());assert!(core::process(&vec![0;65537]).len()<100);}

#[test] fn completed_proof_cache_preserves_every_coefficient_and_cannot_reuse_another_budget(){
 for reserve in [6.0,12.0,18.0]{for enabled in [false,true]{for experimental in [false,true]{
 let mut value=preset(reserve,5);value["eqEnabled"]=serde_json::json!(enabled);value["eqPoints"][2]["gainDb"]=serde_json::json!(4.5);
 let p:core::Preset=serde_json::from_value(value.clone()).unwrap();let old:baseline::Preset=serde_json::from_value(value).unwrap();
 let expected=baseline::prepare_preset(&old,experimental).unwrap();
 for _ in 0..3{let actual=core::prepare_preset(&p,experimental).unwrap();assert_eq!(actual.1.to_bits(),expected.1.to_bits());for(a,b)in actual.0.coeff.iter().zip(expected.0.coeff.iter()){assert_eq!(core::coefficient_bits(a),baseline::coefficient_bits(b));}}
 }}}
 let mut invalid=preset(6.0,1);invalid["eqPoints"][0]["gainDb"]=serde_json::json!(9.0);
 let p:core::Preset=serde_json::from_value(invalid).unwrap();assert!(core::prepare_preset(&p,true).is_ok());assert!(core::prepare_preset(&p,false).is_err());
}
#[test] fn three_paired_local_validation_measurements(){
 use std::time::Instant;
 let previous=preset(12.0,5);let mut pairs=Vec::new();
 for pair in 0..3{
 let run=|cached:bool|{let start=Instant::now();for i in 0..16{let mut request=previous.clone();request["eqPoints"][2]["gainDb"]=serde_json::json!(0.5+i as f64*0.1);
 if cached{let p=core::project_eq_budget(&serde_json::from_value(previous.clone()).unwrap(),&serde_json::from_value(request).unwrap(),Some("p2"),false).unwrap();core::prepare_preset(&p,false).unwrap();}
 else{let p=baseline::project_eq_budget(&serde_json::from_value(previous.clone()).unwrap(),&serde_json::from_value(request).unwrap(),Some("p2"),false).unwrap();baseline::prepare_preset(&p,false).unwrap();}
 }start.elapsed().as_secs_f64()*1000.0/16.0};
 let (old,new)=if pair%2==0{let old=run(false);(old,run(true))}else{let new=run(true);(run(false),new)};pairs.push(serde_json::json!({"pair":pair,"uncachedMs":old,"cachedMs":new}));
 }
 println!("EQ_SHARED_PAIRED {}",serde_json::to_string(&pairs).unwrap());
}
