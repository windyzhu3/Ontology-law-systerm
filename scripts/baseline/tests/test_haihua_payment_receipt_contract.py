from copy import deepcopy
import unittest
from scripts.baseline.haihua_payment_receipt_contract import payment_receipt_projection


class PaymentReceiptRegistrationTest(unittest.TestCase):
    def document(self):
        return {'components': {'schemas': {
            'TerminalRejectionCode': {'enum': ['NOT_FOUND', 'PAYMENT_ALREADY_RECORDED']},
            'ContractProblemV1': {'properties': {'code': {'enum': ['PAYMENT_ALREADY_RECORDED']}}},
        }}}

    def test_removes_only_the_registration_without_mutating_source(self):
        source = self.document()
        unchanged = deepcopy(source)
        result = payment_receipt_projection(source)
        self.assertEqual(source, unchanged)
        expected = deepcopy(source)
        expected['components']['schemas']['TerminalRejectionCode']['enum'].remove('PAYMENT_ALREADY_RECORDED')
        self.assertEqual(result, expected)
        self.assertEqual(result, payment_receipt_projection(result))

    def test_duplicate_or_unregistered_business_code_is_rejected(self):
        duplicate = self.document()
        duplicate['components']['schemas']['TerminalRejectionCode']['enum'].append('PAYMENT_ALREADY_RECORDED')
        with self.assertRaises(ValueError):
            payment_receipt_projection(duplicate)
        unregistered = self.document()
        unregistered['components']['schemas']['ContractProblemV1']['properties']['code']['enum'].clear()
        with self.assertRaises(ValueError):
            payment_receipt_projection(unregistered)

    def test_unreviewed_drift_is_preserved_for_historical_pin_validation(self):
        source = self.document()
        source['components']['schemas']['TerminalRejectionCode']['enum'].append('UNREVIEWED')
        self.assertEqual(['NOT_FOUND', 'UNREVIEWED'],
            payment_receipt_projection(source)['components']['schemas']['TerminalRejectionCode']['enum'])
