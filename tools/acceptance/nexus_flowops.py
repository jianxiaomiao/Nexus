"""Exercise the two applications over HTTP against disposable loopback databases."""
import argparse
import concurrent.futures
import datetime
import http.server
import json
import os
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

NEXUS_TICKETS = "/api/support/tickets"
FLOWOPS_TICKETS = "/integration/v1/tickets"


def setting(name):
    return os.environ["NEXUS_FLOWOPS_TEST_" + name]


def base(name):
    value = setting(name).rstrip("/")
    url = urllib.parse.urlsplit(value)
    if (
        url.scheme not in {"http", "https"} or url.hostname not in {"127.0.0.1", "::1", "localhost"}
        or url.username is not None or url.query or url.fragment
    ):
        raise ValueError(f"{name} must refer to a local disposable backend")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api(url, token, body=None, key=None, method=None):
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    if key is not None:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(url, headers=headers, method=method,
                                     data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect()).open(request, timeout=10) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, None


def expect(result, status):
    code, payload = result
    if code != status:
        raise AssertionError(f"expected HTTP {status}, got {code}")
    return payload


def external_event(label):
    return {
        "schemaVersion": 1,
        "eventId": str(uuid.uuid4()),
        "externalTicketId": str(uuid.uuid4()),
        "title": label,
        "description": "Disposable FlowOps integration acceptance case",
        "submittedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }


def verify_flowops(flow):
    ingest = flow + FLOWOPS_TICKETS
    machine_a = setting("MACHINE_A")
    machine_b = setting("MACHINE_B")
    staff_a1 = setting("STAFF_A1")
    staff_a2 = setting("STAFF_A2")
    staff_b = setting("STAFF_B")
    staff_other_department = setting("STAFF_A_OTHER_DEPARTMENT")
    event = external_event("Concurrent FlowOps import")
    with concurrent.futures.ThreadPoolExecutor(2) as workers:
        replies = list(workers.map(lambda _: api(ingest, machine_a, event), range(2)))
    assert sorted(code for code, _ in replies) == [200, 201], "concurrent external import must replay"
    ack = replies[0][1]
    assert ack == replies[1][1], "duplicate import must return the original receipt"
    expect(api(ingest, machine_a, {**event, "title": "Changed"}), 409)
    expect(api(ingest, machine_a, {**external_event("Foreign route"), "tenantId": 999}), 400)
    expect(api(ingest, staff_a1, external_event("Wrong identity")), 401)

    external_id = ack["flowOpsTicketId"]
    same_ids_other_binding = expect(api(ingest, machine_b, event), 201)
    assert same_ids_other_binding["flowOpsTicketId"] != external_id, "bindings must not share tickets"
    expect(api(f"{flow}/ticket/{external_id}", staff_b), 403)

    ticket_url = f"{flow}/ticket/{external_id}"
    ticket = expect(api(ticket_url, staff_a1), 200)["data"]
    assert ticket["status"] == "ASSIGNED" and ticket["currentAssigneeId"] is None
    version = {"version": ticket["version"]}
    expect(api(ticket_url + "/claim", staff_other_department, version), 404)
    expect(api(ticket_url + "/claim", staff_b, version), 404)

    with concurrent.futures.ThreadPoolExecutor(2) as workers:
        results = list(workers.map(lambda token: api(ticket_url + "/claim", token, version), (staff_a1, staff_a2)))
    assert sorted(code for code, _ in results) == [200, 409], "exactly one claimant must win"
    histories = expect(api(ticket_url + "/transitions", staff_a1), 200)["data"]
    assert sum(item["action"] == "CLAIM" for item in histories) == 1
    assignments = expect(api(ticket_url + "/assignments", staff_a1), 200)["data"]
    assert sum(item["assignmentType"] == "CLAIM" for item in assignments) == 1


def verify_delivery(flow):
    nexus = base("NEXUS_URL") + NEXUS_TICKETS
    user = setting("NEXUS_USER")
    other_user = setting("NEXUS_OTHER_USER")
    staff = setting("STAFF_A1")
    key = str(uuid.uuid4())
    body = {"title": f"Nexus delivery {key}", "description": "Disposable cross-system ticket"}
    created = expect(api(nexus, user, body, key), 201)["data"]
    repeated = expect(api(nexus, user, body, key), 200)["data"]
    assert created["id"] == repeated["id"], "client retries must retain the same Nexus ticket"
    expect(api(nexus, user, {**body, "title": "Changed"}, key), 409)
    url = f"{nexus}/{created['id']}"
    expect(api(url, other_user), 404)

    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        status = expect(api(url, user), 200)["data"]
        if status["deliveryStatus"] == "DELIVERED":
            ticket_no = status["flowOpsTicketNo"]
            assert ticket_no, "delivery must include a FlowOps ticket number"
            query = urllib.parse.urlencode({"title": body["title"], "pageSize": 10})
            page = expect(api(f"{flow}/ticket/page?{query}", staff), 200)["data"]
            assert page["total"] == 1 and len(page["records"]) == 1, "expected exactly one FlowOps ticket"
            ticket = page["records"][0]
            assert ticket["title"] == body["title"] and ticket["ticketNo"] == ticket_no, "ACK must match FlowOps ticket"
            print(f"PASS Nexus {created['id']} delivered; FlowOps has one matching ticket {ticket_no}")
            return
        if status["deliveryStatus"] == "BLOCKED":
            raise AssertionError("delivery entered BLOCKED")
        time.sleep(1)
    raise AssertionError("delivery did not reach DELIVERED within 30 seconds")


def drop_first_ack(port):
    flowops = base("FLOWOPS_URL") + FLOWOPS_TICKETS
    lost = set()
    lock = threading.Lock()

    class Proxy(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/flowOps" + FLOWOPS_TICKETS:
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 65536:
                self.send_error(400)
                return
            body = self.rfile.read(length)
            headers = {"Content-Type": "application/json"}
            for name in ("Authorization", "X-Trace-Id"):
                if name in self.headers:
                    headers[name] = self.headers[name]
            request = urllib.request.Request(flowops, headers=headers, data=body, method="POST")
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
            try:
                response = opener.open(request, timeout=5)
            except urllib.error.HTTPError as error:
                response = error
            except OSError:
                self.send_error(502)
                return
            with response:
                status = response.code
                receipt = response.read(65536)
            if status in (200, 201):
                event = str(uuid.UUID(json.loads(receipt)["eventId"]))
                with lock:
                    first = event not in lost
                    if first:
                        lost.add(event)
                if first:
                    print(f"Dropped FlowOps ACK after HTTP {status}, event={event}", flush=True)
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                print(f"Forwarded replay ACK after HTTP {status}, event={event}", flush=True)
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(receipt)))
            self.end_headers()
            self.wfile.write(receipt)

        def log_message(self, format, *args):
            pass

    with http.server.ThreadingHTTPServer(("127.0.0.1", port), Proxy) as server:
        print(f"FlowOps ACK loss test relay listening on port {port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--drop-first-ack", type=int, metavar="PORT", help="run a loopback fault-injection relay")
    mode.add_argument("--deliver-only", action="store_true", help="verify Nexus delivery after FlowOps acceptance")
    args = parser.parse_args()
    if args.drop_first_ack is not None:
        drop_first_ack(args.drop_first_ack)
    else:
        flow = base("FLOWOPS_URL")
        if not args.deliver_only:
            verify_flowops(flow)
        verify_delivery(flow)
