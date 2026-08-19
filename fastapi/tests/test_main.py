"""HTTP 계층 테스트. 요청/응답 스키마가 Java 클라이언트와 맞는지 고정한다."""

import os

os.environ["NLTK_DATA"] = os.path.join(os.path.dirname(__file__), "no-such-nltk-data")

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_hints_response_shape():
    response = client.post("/morphology/hints", json={"text": "nagsulat"})

    assert response.status_code == 200
    body = response.json()
    assert body["text"] == "nagsulat"
    assert "nagsulat = nag + sulat" in body["hint"]
    assert body["tokens"][0] == {
        "token": "nagsulat",
        "stem": "sulat",
        "prefix": "nag",
        "infix": None,
        "suffix": None,
        "reduplication": None,
    }


def test_hints_without_affixes_returns_empty_hint():
    response = client.post("/morphology/hints", json={"text": "ang ko"})

    assert response.status_code == 200
    assert response.json()["hint"] == ""
    assert response.json()["tokens"] == []


def test_text_is_required():
    response = client.post("/morphology/hints", json={})

    assert response.status_code == 422


def test_empty_text_is_accepted():
    # 빈 문자열은 유효한 입력이다. 힌트 없이 통과시킨다.
    response = client.post("/morphology/hints", json={"text": ""})

    assert response.status_code == 200
    assert response.json()["hint"] == ""
