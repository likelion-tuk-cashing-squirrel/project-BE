from fastapi import FastAPI

app = FastAPI(title="borderless-fastapi")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
