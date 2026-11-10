// 실행: node src/test/js/rule-plan.test.js  (프레임워크 없음, 실패하면 assert 예외로 종료 코드 1)
const assert = require("assert");
const path = require("path");
const { tickFloor, planLevels, stageOf, badFields, validPlan } = require(path.join(__dirname, "../../main/resources/static/js/rule-plan.js"));

// 호가 내림 경계값 (2023-01 개편 현행: 2,000원 미만 1원)
assert.strictEqual(tickFloor(999), 999);
assert.strictEqual(tickFloor(1003), 1003);
assert.strictEqual(tickFloor(1999), 1999);
assert.strictEqual(tickFloor(2000), 2000);
assert.strictEqual(tickFloor(2003), 2000);      // 2,000 이상은 5원
assert.strictEqual(tickFloor(4999), 4995);
assert.strictEqual(tickFloor(5009), 5000);      // 5,000 이상은 10원
assert.strictEqual(tickFloor(19999), 19990);
assert.strictEqual(tickFloor(20049), 20000);    // 20,000 이상은 50원
assert.strictEqual(tickFloor(49999), 49950);
assert.strictEqual(tickFloor(50099), 50000);    // 50,000 이상은 100원
assert.strictEqual(tickFloor(199999), 199900);
assert.strictEqual(tickFloor(200499), 200000);  // 200,000 이상은 500원
assert.strictEqual(tickFloor(499999), 499500);
assert.strictEqual(tickFloor(500999), 500000);  // 500,000 이상은 1,000원

const plan = { base: 10000, steps: [0, -5, -10], stop: -15, target: 20 };
const lv = planLevels(plan);
assert.deepStrictEqual(lv.steps.map(function(s) { return s.price; }), [10000, 9500, 9000]);
assert.strictEqual(lv.stop.price, 8500);
assert.strictEqual(lv.target.price, 12000);

assert.strictEqual(planLevels({ base: 61234, steps: [0, -5, -10], stop: -15, target: 20 }).steps[0].price, 61200);

const st = stageOf(lv, 9400);
assert.deepStrictEqual(st.reached, [true, true, false]);   // 2단계 도달, 3단계 미도달
assert.strictEqual(st.next.n, 3);
assert.strictEqual(st.stopHit, false);
assert.strictEqual(stageOf(lv, 8500).stopHit, true);
assert.strictEqual(stageOf(lv, 12000).targetHit, true);

// 입력 검사: 음수·0·비정상 값 거부
assert.ok(validPlan(plan));
assert.strictEqual(validPlan(Object.assign({}, plan, { base: 0 })), null);
assert.strictEqual(validPlan(Object.assign({}, plan, { base: -1 })), null);
assert.strictEqual(validPlan(Object.assign({}, plan, { base: NaN })), null);
assert.strictEqual(validPlan(Object.assign({}, plan, { steps: [0, 5, -10] })), null);
assert.strictEqual(validPlan(Object.assign({}, plan, { stop: 0 })), null);
assert.strictEqual(validPlan(Object.assign({}, plan, { target: -3 })), null);
assert.strictEqual(validPlan(Object.assign({}, plan, { target: 1001 })), null);
// 단계는 내림차순, 손절은 마지막 단계보다 아래, 경계 -99 포함
assert.deepStrictEqual(badFields(Object.assign({}, plan, { steps: [0, -10, -5] })), ["step3"]);
assert.deepStrictEqual(badFields(Object.assign({}, plan, { steps: [0, -5, -5] })), ["step3"]);
assert.deepStrictEqual(badFields(Object.assign({}, plan, { stop: -10 })), ["stop"]);   // 3단계(-10)와 같으면 거부
assert.deepStrictEqual(badFields(Object.assign({}, plan, { stop: -5 })), ["stop"]);
assert.deepStrictEqual(badFields(Object.assign({}, plan, { steps: [0, -50, -99], stop: -99 })), ["stop"]);
assert.ok(validPlan(Object.assign({}, plan, { steps: [-1, -50, -98], stop: -99 })));
assert.deepStrictEqual(badFields(Object.assign({}, plan, { steps: [1, -5, -10] })), ["step1"]);
assert.deepStrictEqual(badFields(Object.assign({}, plan, { base: 0, target: 0 })), ["base", "target"]);
assert.strictEqual(validPlan(Object.assign({}, plan, { steps: [0, -5] })), null);
assert.strictEqual(validPlan(null), null);

console.log("rule-plan.test.js: ok");
