"""Default-off exact source CATREA28/PSSA to native CATREA52/L28 pairing.

No RESTRN120 admission, bitset conversion, registry mutation or paint claim.
"""
CATREA28_PAIRING_POLICY = 'source_catrea28_pssa_to_native52_28_v1'
CATREA28_PAIRING_CONTRACT_SHA256 = '221c260ef9d9847c0bc9225396bdd89ac8ef3f7511c8dff36411d07b3388f6de'


def paired_catrea_values(native_code, policy):
    if type(policy) is not str or policy != CATREA28_PAIRING_POLICY:
        raise ValueError('EXPLICIT_CATREA28_PSSA_PAIRING_POLICY_REQUIRED')
    if type(native_code) is not int or native_code != 52:
        raise ValueError('CATREA28_PAIRING_ONLY_NATIVE_CATREA52')
    return (28,)
