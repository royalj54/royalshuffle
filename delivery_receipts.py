"""Pending-only evidence for acknowledged exact ordered submissions."""
import hashlib
import json
import uuid


def payload_digest(uris):
    return hashlib.sha256(json.dumps(uris, separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()


def delivery_plan(operation_id, output_id, uris):
    return {"operation_id": operation_id, "attempt_id": str(uuid.uuid4()),
            "output_id": output_id, "payload_digest": payload_digest(uris), "receipts": []}


def expected_writes(uris):
    return [("PUT", 0, 0, [])] + [("POST", start, min(start + 100, len(uris)), uris[start:start + 100])
                                  for start in range(0, len(uris), 100)]


def validate_delivery(evidence, operation_id, output_id, uris):
    if not isinstance(evidence, dict) or set(evidence) != {"operation_id", "attempt_id", "output_id", "payload_digest", "receipts"}:
        raise ValueError("Malformed delivery evidence.")
    try:
        valid_attempt = str(uuid.UUID(evidence["attempt_id"])) == evidence["attempt_id"]
    except (ValueError, TypeError, AttributeError):
        valid_attempt = False
    if (not valid_attempt or evidence["operation_id"] != operation_id or evidence["output_id"] != output_id
            or output_id is None or evidence["payload_digest"] != payload_digest(uris)):
        raise ValueError("Delivery evidence does not match pending identity/payload.")
    receipts = evidence["receipts"]
    writes = expected_writes(uris)
    if not isinstance(receipts, list) or len(receipts) > len(writes):
        raise ValueError("Invalid delivery coverage.")
    for index, receipt in enumerate(receipts):
        method, start, end, batch = writes[index]
        if (not isinstance(receipt, dict) or set(receipt) != {"attempt_id", "index", "method", "start", "end", "payload_digest", "status", "snapshot_id"}
                or receipt["attempt_id"] != evidence["attempt_id"]
                or type(receipt["index"]) is not int or receipt["index"] != index
                or receipt["method"] != method or type(receipt["start"]) is not int or receipt["start"] != start
                or type(receipt["end"]) is not int or receipt["end"] != end
                or receipt["payload_digest"] != payload_digest(batch)
                or type(receipt["status"]) is not int or receipt["status"] != (200 if method == "PUT" else 201)
                or not isinstance(receipt["snapshot_id"], str) or not receipt["snapshot_id"].strip()):
            raise ValueError("Invalid Spotify write receipt.")
    return len(receipts) == len(writes)
