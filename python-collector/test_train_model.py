"""train_model 저장 판정 자가점검 - 실행: python test_train_model.py"""
from train_model import passes_auc_gate

if __name__ == "__main__":
    assert [passes_auc_gate(a) for a in (0.55, 0.60, 0.50, float("nan"), None)] == [True, True, False, False, False]
    print("train_model self-check passed")
