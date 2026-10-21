"""Integration checks against the running Docker API."""
import json
import uuid
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError


def call(path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request("http://127.0.0.1:8000" + path,
                      data=None if body is None else json.dumps(body).encode(),
                      headers=headers)
    try:
        with urlopen(request, timeout=15) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.load(error)


assert call("/health")[0] == 200
username = "Test" + uuid.uuid4().hex[:16]
body = {"username": username, "name": "Backend test", "password": "Simple123!"}
status, data = call("/signup", body)
assert status == 201, data
assert data["user"]["last_logged_on"] is None
assert call("/me", token=data["token"])[1]["user"]["last_logged_on"] is None
assert "password" not in data["user"] and "password_hash" not in data["user"]
assert call("/me", token=data["token"])[0] == 200
assert call("/signout", {}, token=data["token"])[0] == 200
assert call("/me", token=data["token"])[0] == 401
assert call("/me", token="wrong")[0] == 401
assert call("/me")[0] == 401
assert call("/signup", dict(body, username=username.lower()))[0] == 409
status, signed_in = call("/signin", dict(body, username=username.upper()))
assert status == 200
previous = signed_in["user"]["last_logged_on"]
assert isinstance(previous, int) and previous <= int(time.time() * 1000)
assert call("/me", token=signed_in["token"])[1]["user"]["last_logged_on"] == previous
assert call("/signin", dict(body, password="Wrong123"))[0] == 401
assert call("/me", token=signed_in["token"])[1]["user"]["last_logged_on"] == previous
for password in ["Short1", "lettersOnly", "12345678"]:
    assert call("/signup", dict(body, password=password))[0] == 400
for invalid in ["bad_name", "bad name", "bad-name", "", "éclair1"]:
    assert call("/signup", dict(body, username=invalid))[0] == 400
assert call("/signup", dict(body, name="   "))[0] == 400
assert call("/signup", {"username": 42, "password": []})[0] == 400
assert call("/signup", dict(body, username="Test" + uuid.uuid4().hex[:16],
                            password="Simple123"))[0] == 201
print("PASS: signup, signin, uniqueness, validation, password checks, sessions.")
